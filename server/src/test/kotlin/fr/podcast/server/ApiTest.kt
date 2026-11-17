package fr.podcast.server

import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiTest {

    @BeforeTest
    fun reset() = TestDatabase.reset()

    @Test
    fun anonymousRootDescribesHowToSignIn() = testApplication {
        podcastServer()

        val root = client.get("/") { accept(HalForms) }.json()

        assertEquals("Podcast (test)", root["name"]!!.jsonPrimitive.content)
        assertEquals("/auth/tokens", root["_links"]!!.jsonObject["tokens"]!!.jsonObject["href"]!!.jsonPrimitive.content)
        val template = root["_templates"]!!.jsonObject["create-token"]!!.jsonObject
        assertEquals("POST", template["method"]!!.jsonPrimitive.content)
        assertNull(root["_embedded"])
    }

    @Test
    fun eachUserGetsTheirOwnHome() = testApplication {
        podcastServer()

        val alice = client.getJson("/", client.token())
        val bob = client.getJson("/", client.token(TestSeed.BOB, TestSeed.BOB_PASSWORD))

        fun firstResumed(root: kotlinx.serialization.json.JsonObject) =
            root["_embedded"]!!.jsonObject["resume"]!!.jsonArray.first().jsonObject["title"]!!.jsonPrimitive.content
        assertEquals("LCC 330 - Les nouveautés de Kotlin 2.4", firstResumed(alice))
        assertEquals("Compose Multiplatform for iOS is stable", firstResumed(bob))
        fun link(rel: String) = alice["_links"]!!.jsonObject[rel]!!.jsonObject["href"]!!.jsonPrimitive.content
        assertEquals("/episodes{?status,offset,limit}", link("episodes"))
        // A subscription shared with the user is not in their list: they reach it by this link.
        assertEquals("/subscriptions/{id}", link("subscription"))
        assertEquals("/playlists/{id}", link("playlist"))
    }

    @Test
    fun wrongPasswordAndRevokedTokensAreRejected() = testApplication {
        podcastServer()

        val wrong = client.post("/auth/tokens") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"alice","password":"nope","deviceName":"test"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)

        val token = client.token()
        assertEquals(HttpStatusCode.OK, client.get("/me") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/auth/tokens/current") { bearerAuth(token) }.status)

        val afterRevocation = client.get("/me") { bearerAuth(token) }
        assertEquals(HttpStatusCode.Unauthorized, afterRevocation.status)
        assertTrue(afterRevocation.headers[HttpHeaders.WWWAuthenticate]!!.startsWith("Bearer"))
    }

    @Test
    fun episodeTemplatesFollowItsState() = testApplication {
        podcastServer()
        val token = client.token()

        val fresh = client.getJson("/episodes/1", token)["_templates"]!!.jsonObject
        assertEquals(setOf("update-progress", "mark-played", "keep", "favorite", "rate", "download", "recommend", "enqueue"), fresh.keys)

        assertEquals(HttpStatusCode.NoContent, client.post("/episodes/1/played") { bearerAuth(token) }.status)

        val played = client.getJson("/episodes/1", token)
        assertEquals("PLAYED", played["status"]!!.jsonPrimitive.content)
        assertEquals(setOf("update-progress", "mark-unplayed", "keep", "favorite", "rate", "download", "recommend", "enqueue"), played["_templates"]!!.jsonObject.keys)

        // Plain HAL has no actions.
        assertNull(client.getJson("/episodes/1", token, accept = ContentType("application", "hal+json"))["_templates"])
    }

    @Test
    fun progressIsValidatedAndSaved() = testApplication {
        podcastServer()
        val token = client.token()

        val tooFar = client.put("/episodes/1/progress") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"positionSeconds": 999999}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, tooFar.status)
        assertEquals("positionSeconds", tooFar.json()["errors"]!!.jsonArray.first().jsonObject["field"]!!.jsonPrimitive.content)

        val saved = client.put("/episodes/1/progress") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"positionSeconds": 120}""")
        }
        assertEquals(HttpStatusCode.NoContent, saved.status)
        val episode = client.getJson("/episodes/1", token)
        assertEquals(120, episode["positionSeconds"]!!.jsonPrimitive.int)
        assertEquals("IN_PROGRESS", episode["status"]!!.jsonPrimitive.content)

        // Beyond the duration announced by the feed (52 min), which is often approximate.
        val pastFeedDuration = client.put("/episodes/1/progress") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"positionSeconds": 3200}""")
        }
        assertEquals(HttpStatusCode.NoContent, pastFeedDuration.status)
    }

    @Test
    fun listeningStateIsPerUser() = testApplication {
        podcastServer()
        val alice = client.token()
        val bob = client.token(TestSeed.BOB, TestSeed.BOB_PASSWORD)

        client.post("/episodes/1/played") { bearerAuth(alice) }

        assertEquals("PLAYED", client.getJson("/episodes/1", alice)["status"]!!.jsonPrimitive.content)
        assertEquals("IN_PROGRESS", client.getJson("/episodes/1", bob)["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun collectionsArePaginatedAndFiltered() = testApplication {
        podcastServer()
        val token = client.token()

        val first = client.getJson("/episodes?limit=4", token)
        assertEquals(9, first["total"]!!.jsonPrimitive.int)
        assertEquals(4, first["count"]!!.jsonPrimitive.int)
        assertEquals("/episodes?offset=4&limit=4", first["_links"]!!.jsonObject["next"]!!.jsonObject["href"]!!.jsonPrimitive.content)
        assertNull(first["_links"]!!.jsonObject["prev"])

        val inProgress = client.getJson("/episodes?status=IN_PROGRESS", token)
        assertEquals(2, inProgress["total"]!!.jsonPrimitive.int)

        // Bob only sees the episodes of his two subscriptions.
        assertEquals(5, client.getJson("/episodes", client.token(TestSeed.BOB, TestSeed.BOB_PASSWORD))["total"]!!.jsonPrimitive.int)

        assertEquals(HttpStatusCode.BadRequest, client.get("/episodes?limit=1000") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/episodes?status=LATER") { bearerAuth(token) }.status)
    }

    @Test
    fun unknownResourcesAreNotFound() = testApplication {
        podcastServer()
        val token = client.token()

        assertEquals(HttpStatusCode.NotFound, client.get("/episodes/999") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/episodes/abc") { bearerAuth(token) }.status)
        // Alice's subscription is not visible to Bob (Alice, an administrator, would see Bob's).
        val bob = client.token(TestSeed.BOB, TestSeed.BOB_PASSWORD)
        assertEquals(HttpStatusCode.NotFound, client.get("/subscriptions/1") { bearerAuth(bob) }.status)
    }

    @Test
    fun audioRedirectsToTheOriginalFile() = testApplication {
        podcastServer()
        val token = client.token()
        val noRedirect = createClient { followRedirects = false }

        val response = noRedirect.get("/episodes/1/audio") { bearerAuth(token) }

        assertEquals(HttpStatusCode.Found, response.status)
        assertNotNull(response.headers[HttpHeaders.Location])
    }
}
