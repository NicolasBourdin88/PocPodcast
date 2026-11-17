package fr.podcast.server

import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubscriptionsTest {

    @BeforeTest
    fun reset() = TestDatabase.reset()

    private suspend fun HttpClient.subscribe(token: String, feedUrl: String): HttpResponse = post("/subscriptions") {
        bearerAuth(token)
        accept(HalForms)
        contentType(ContentType.Application.Json)
        setBody("""{"feedUrl":"$feedUrl"}""")
    }

    private suspend fun HttpClient.patchSubscription(token: String, id: String, body: String): HttpResponse = patch("/subscriptions/$id") {
        bearerAuth(token)
        accept(HalForms)
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    @Test
    fun subscribingReadsTheFeedOnceForEveryone() = testApplication {
        podcastServer()
        val alice = client.token()
        val bob = client.token(TestSeed.BOB, TestSeed.BOB_PASSWORD)

        val created = client.subscribe(alice, "feeds.test/podcast20.xml") // https:// is added
        assertEquals(HttpStatusCode.Created, created.status)
        val subscription = created.json()
        assertEquals("/subscriptions/${subscription["id"]!!.jsonPrimitive.content}", created.headers[HttpHeaders.Location])
        val podcast = subscription["_embedded"]!!.jsonObject["podcast"]!!.jsonObject
        assertEquals("Le Podcast de Test", podcast["title"]!!.jsonPrimitive.content)
        assertEquals("OK", podcast["refreshStatus"]!!.jsonPrimitive.content)
        assertEquals(setOf("update", "unsubscribe", "share"), subscription["_templates"]!!.jsonObject.keys)

        // Subscribing again changes nothing; Bob gets his own subscription to the same podcast.
        assertEquals(HttpStatusCode.OK, client.subscribe(alice, "https://feeds.test/podcast20.xml").status)
        val bobs = client.subscribe(bob, "https://feeds.test/podcast20.xml").json()
        assertEquals(podcast["id"], bobs["_embedded"]!!.jsonObject["podcast"]!!.jsonObject["id"])

        val episodes = client.getJson("/podcasts/${podcast["id"]!!.jsonPrimitive.content}/episodes", alice)
        assertEquals(3, episodes["total"]!!.jsonPrimitive.int)
        val latest = episodes["_embedded"]!!.jsonObject["episodes"]!!.jsonArray.map { it.jsonObject }
            .first { it["title"]!!.jsonPrimitive.content == "Épisode 3 : Podcast 2.0" }
        assertEquals(3930, latest["durationSeconds"]!!.jsonPrimitive.int)
        val links = latest["_links"]!!.jsonObject
        assertEquals("https://test.example.org/ep3/chapters.json", links["chapters"]!!.jsonObject["href"]!!.jsonPrimitive.content)
        assertEquals(listOf("text/vtt", "application/x-subrip"), links["transcript"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertEquals("fr", links["transcript"]!!.jsonArray.first().jsonObject["hreflang"]!!.jsonPrimitive.content)
    }

    @Test
    fun refusedFeedsExplainWhy() = testApplication {
        podcastServer()
        val token = client.token()

        fun type(response: suspend () -> HttpResponse) = kotlinx.coroutines.runBlocking {
            val answer = response()
            assertEquals(HttpStatusCode.UnprocessableEntity, answer.status)
            answer.json()["type"]!!.jsonPrimitive.content
        }
        assertEquals("/problems/not-a-feed", type { client.subscribe(token, "https://feeds.test/not-a-feed.html") })
        assertEquals("/problems/not-a-feed", type { client.subscribe(token, "https://feeds.test/atom.xml") })
        assertEquals("/problems/feed-unreachable", type { client.subscribe(token, "https://feeds.test/missing.xml") })
        assertEquals("/problems/invalid-feed-url", type { client.subscribe(token, "ftp://feeds.test/podcast20.xml") })
        assertEquals("about:blank", type { client.subscribe(token, " ") })
    }

    @Test
    fun subscriptionsAreAnnotatedPartially() = testApplication {
        podcastServer()
        val token = client.token()

        val noted = client.patchSubscription(token, "1", """{"note":"  À écouter en voiture  "}""").json()
        assertEquals("À écouter en voiture", noted["note"]!!.jsonPrimitive.content)
        assertFalse(noted["autoDownload"]!!.jsonPrimitive.boolean)

        val automatic = client.patchSubscription(token, "1", """{"autoDownload":true}""").json()
        assertEquals("À écouter en voiture", automatic["note"]!!.jsonPrimitive.content) // absent: unchanged
        assertTrue(automatic["autoDownload"]!!.jsonPrimitive.boolean)

        assertEquals(JsonNull, client.patchSubscription(token, "1", """{"note":null}""").json()["note"])
        assertEquals(HttpStatusCode.UnprocessableEntity, client.patchSubscription(token, "1", """{"autoDownload":"maybe"}""").status)
        assertEquals(HttpStatusCode.UnprocessableEntity, client.patchSubscription(token, "1", """{"note":"${"x".repeat(2001)}"}""").status)
        // Alice's subscription does not exist for Bob (Alice, an administrator, would see Bob's).
        val bob = client.token(TestSeed.BOB, TestSeed.BOB_PASSWORD)
        assertEquals(HttpStatusCode.NotFound, client.patchSubscription(bob, "1", """{"note":"hack"}""").status)
    }

    @Test
    fun aPodcastGoesWithItsLastSubscriber() = testApplication {
        podcastServer()
        val alice = client.token()
        val bob = client.token(TestSeed.BOB, TestSeed.BOB_PASSWORD)

        // Talking Kotlin (podcast 2): Alice's subscription 2, Bob's subscription 5.
        assertEquals(HttpStatusCode.NoContent, client.delete("/subscriptions/2") { bearerAuth(alice) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/podcasts/2") { bearerAuth(bob) }.status)

        assertEquals(HttpStatusCode.NoContent, client.delete("/subscriptions/5") { bearerAuth(bob) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/podcasts/2") { bearerAuth(bob) }.status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/subscriptions/5") { bearerAuth(bob) }.status)
        assertEquals(3, client.getJson("/subscriptions", alice)["count"]!!.jsonPrimitive.int)
    }

    @Test
    fun feedsAreReadAgainOnDemand() = testApplication {
        podcastServer()
        val token = client.token()
        val podcastId = client.subscribe(token, "https://feeds.test/podcast20.xml").json()["_embedded"]!!
            .jsonObject["podcast"]!!.jsonObject["id"]!!.jsonPrimitive.content

        val refreshed = client.post("/podcasts/$podcastId/refresh") { bearerAuth(token) }.json()
        assertEquals("OK", refreshed["refreshStatus"]!!.jsonPrimitive.content)

        // The seeded "Les Cast Codeurs" points to a feed that does not exist: recorded, not thrown.
        val failed = client.post("/podcasts/1/refresh") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, failed.status)
        assertEquals("FAILED", failed.json()["refreshStatus"]!!.jsonPrimitive.content)
    }

    @Test
    fun keptEpisodesStayKept() = testApplication {
        podcastServer()
        val token = client.token()

        assertEquals(HttpStatusCode.NoContent, client.put("/episodes/4/kept") { bearerAuth(token) }.status)
        val kept = client.getJson("/episodes/4", token)
        assertTrue(kept["kept"]!!.jsonPrimitive.boolean)
        assertTrue("unkeep" in kept["_templates"]!!.jsonObject)

        // Marking it unplayed keeps the mark.
        client.delete("/episodes/4/played") { bearerAuth(token) }
        val unplayed = client.getJson("/episodes/4", token)
        assertEquals("NEW", unplayed["status"]!!.jsonPrimitive.content)
        assertTrue(unplayed["kept"]!!.jsonPrimitive.boolean)

        assertEquals(HttpStatusCode.NoContent, client.delete("/episodes/4/kept") { bearerAuth(token) }.status)
        assertFalse(client.getJson("/episodes/4", token)["kept"]!!.jsonPrimitive.boolean)
    }
}
