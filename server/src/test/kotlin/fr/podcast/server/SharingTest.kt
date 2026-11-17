package fr.podcast.server

import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Shares (ADR 0005, 0009). Seed: Alice (administrator, user 1) owns subscriptions 1 to 4
 * (1 = Les Cast Codeurs), Bob (user 2) owns 5 and 6; a third account, Carol, is created when needed.
 */
class SharingTest {

    @BeforeTest
    fun reset() = TestDatabase.reset()

    private suspend fun HttpClient.send(method: HttpMethod, path: String, token: String, body: String? = null): HttpResponse = request(path) {
        this.method = method
        bearerAuth(token)
        accept(HalForms)
        body?.let {
            contentType(ContentType.Application.Json)
            setBody(it)
        }
    }

    private suspend fun HttpClient.shared(token: String): List<JsonObject> =
        getJson("/shared", token)["_embedded"]!!.jsonObject["shares"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.templates(): Set<String> = (this["_templates"] as? JsonObject)?.keys.orEmpty()

    @Test
    fun aSubscriptionSharedReadOnlyCanBeSeenNotChanged() = testApplication {
        podcastServer()
        val alice = client.token()
        val bob = client.token(TestSeed.BOB, TestSeed.BOB_PASSWORD)

        // Not shared: Bob cannot even see it.
        assertEquals(HttpStatusCode.NotFound, client.send(HttpMethod.Get, "/subscriptions/1", bob).status)

        val share = client.send(HttpMethod.Post, "/subscriptions/1/shares", alice, """{"userId":"2","message":"Le meilleur podcast Java"}""")
        assertEquals(HttpStatusCode.OK, share.status)
        val shareId = share.json()["id"]!!.jsonPrimitive.content

        val seen = client.getJson("/subscriptions/1", bob)
        assertEquals("READ", seen["access"]!!.jsonPrimitive.content)
        assertEquals("Alice", seen["ownerName"]!!.jsonPrimitive.content)
        assertEquals(setOf("subscribe"), seen.templates()) // to follow it oneself
        assertEquals(HttpStatusCode.Forbidden, client.send(HttpMethod.Patch, "/subscriptions/1", bob, """{"note":"Mine now"}""").status)
        assertEquals(HttpStatusCode.Forbidden, client.send(HttpMethod.Delete, "/subscriptions/1", bob).status)
        assertEquals(HttpStatusCode.Forbidden, client.send(HttpMethod.Get, "/subscriptions/1/shares", bob).status)

        // Bob finds it among what is shared with him, with the message.
        val received = client.shared(bob).single()
        assertEquals("SUBSCRIPTION", received["type"]!!.jsonPrimitive.content)
        assertEquals("Le meilleur podcast Java", received["message"]!!.jsonPrimitive.content)
        assertEquals("Les Cast Codeurs", received["_embedded"]!!.jsonObject["subscription"]!!.jsonObject["_embedded"]!!.jsonObject["podcast"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals(setOf("unshare"), received.templates()) // he may decline, not change it

        // Alice lets him modify: the note and the automatic download, still not unsubscribe.
        assertEquals(HttpStatusCode.Forbidden, client.send(HttpMethod.Patch, "/shares/$shareId", bob, """{"permission":"WRITE"}""").status)
        assertEquals(HttpStatusCode.OK, client.send(HttpMethod.Patch, "/shares/$shareId", alice, """{"permission":"WRITE"}""").status)
        assertEquals(setOf("update", "subscribe"), client.getJson("/subscriptions/1", bob).templates())
        val noted = client.send(HttpMethod.Patch, "/subscriptions/1", bob, """{"note":"Écouté en famille"}""")
        assertEquals(HttpStatusCode.OK, noted.status)
        assertEquals("Écouté en famille", client.getJson("/subscriptions/1", alice)["note"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.Forbidden, client.send(HttpMethod.Delete, "/subscriptions/1", bob).status)

        val shares = client.getJson("/subscriptions/1/shares", alice)["_embedded"]!!.jsonObject["shares"]!!.jsonArray
        assertEquals("Bob", shares.single().jsonObject["grantee"]!!.jsonObject["displayName"]!!.jsonPrimitive.content)

        // Bob declines: the subscription is out of sight again.
        assertEquals(HttpStatusCode.NoContent, client.send(HttpMethod.Delete, "/shares/$shareId", bob).status)
        assertEquals(HttpStatusCode.NotFound, client.send(HttpMethod.Get, "/subscriptions/1", bob).status)
        assertEquals(HttpStatusCode.NotFound, client.send(HttpMethod.Get, "/shares/$shareId", alice).status)
    }

    @Test
    fun anEpisodeIsRecommendedReadOnly() = testApplication {
        podcastServer()
        val alice = client.token()
        val bob = client.token(TestSeed.BOB, TestSeed.BOB_PASSWORD)

        val recommendation = client.send(HttpMethod.Post, "/episodes/2/shares", bob, """{"userId":"1","message":"À partir de 12 min"}""")
        assertEquals(HttpStatusCode.OK, recommendation.status)
        val shareId = recommendation.json()["id"]!!.jsonPrimitive.content
        assertEquals("READ", recommendation.json()["permission"]!!.jsonPrimitive.content)

        val received = client.shared(alice).single()
        assertEquals("EPISODE", received["type"]!!.jsonPrimitive.content)
        assertEquals("Bob", received["owner"]!!.jsonObject["displayName"]!!.jsonPrimitive.content)
        val episode = received["_embedded"]!!.jsonObject["episode"]!!.jsonObject
        assertEquals("LCC 330 - Les nouveautés de Kotlin 2.4", episode["title"]!!.jsonPrimitive.content)
        assertEquals(38 * 60, episode["positionSeconds"]!!.jsonPrimitive.content.toInt()) // Alice's own position

        // A recommendation stays read-only, even for its owner.
        assertFalse("change-permission" in client.getJson("/shares/$shareId", bob).templates())
        assertEquals(HttpStatusCode.UnprocessableEntity, client.send(HttpMethod.Patch, "/shares/$shareId", bob, """{"permission":"WRITE"}""").status)
        assertEquals(
            HttpStatusCode.UnprocessableEntity,
            client.send(HttpMethod.Post, "/episodes/2/shares", bob, """{"userId":"1","permission":"WRITE"}""").status,
        )

        assertEquals(HttpStatusCode.NoContent, client.send(HttpMethod.Delete, "/shares/$shareId", alice).status)
        assertTrue(client.shared(alice).isEmpty())
    }

    @Test
    fun invalidSharesAreExplained() = testApplication {
        podcastServer()
        val alice = client.token()

        suspend fun error(body: String): JsonObject {
            val response = client.send(HttpMethod.Post, "/subscriptions/1/shares", alice, body)
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            return response.json()["errors"]!!.jsonArray.first().jsonObject
        }
        suspend fun field(body: String): String = error(body)["field"]!!.jsonPrimitive.content
        assertEquals("userId", field("""{"userId":"999"}"""))
        assertEquals("userId", field("""{"userId":"1"}""")) // the owner herself
        assertEquals("permission", field("""{"userId":"2","permission":"ADMIN"}"""))
        val tooLong = error("""{"userId":"2","message":"${"x".repeat(501)}"}""")
        assertEquals("message", tooLong["field"]!!.jsonPrimitive.content)
        assertEquals("at most 500 characters", tooLong["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun sharesGoWithWhatTheyShare() = testApplication {
        podcastServer()
        val alice = client.token()
        val bob = client.token(TestSeed.BOB, TestSeed.BOB_PASSWORD)
        client.send(HttpMethod.Post, "/subscriptions/1/shares", alice, """{"userId":"2"}""")
        client.send(HttpMethod.Post, "/episodes/3/shares", alice, """{"userId":"2"}""") // Fragmented
        assertEquals(2, client.shared(bob).size)

        // Alice unsubscribes from Les Cast Codeurs: Bob no longer sees it.
        client.send(HttpMethod.Delete, "/subscriptions/1", alice)
        assertEquals(listOf("EPISODE"), client.shared(bob).map { it["type"]!!.jsonPrimitive.content })

        // Fragmented disappears when nobody follows it: so does the recommendation.
        client.send(HttpMethod.Delete, "/subscriptions/4", alice)
        client.send(HttpMethod.Delete, "/subscriptions/6", bob)
        assertTrue(client.shared(bob).isEmpty())
    }

    @Test
    fun administratorsSeeEveryonesSubscriptions() = testApplication {
        podcastServer()
        val alice = client.token()
        val bob = client.token(TestSeed.BOB, TestSeed.BOB_PASSWORD)

        val bobs = client.getJson("/subscriptions/5", alice)
        assertEquals("OWNER", bobs["access"]!!.jsonPrimitive.content)
        assertEquals("Bob", bobs["ownerName"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.NotFound, client.send(HttpMethod.Get, "/subscriptions/2", bob).status)
    }
}
