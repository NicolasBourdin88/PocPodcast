package fr.podcast.server

import fr.podcast.server.catalog.UserCatalog
import fr.podcast.server.sync.podcastGuid
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.Base64
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The subscriptions of the Open Podcast API v1 (ADR 0011). */
class OpenPodcastApiTest {

    @BeforeTest
    fun reset() = TestDatabase.reset()

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpClient.opa(token: String, path: String) = get(path) { bearerAuth(token) }

    @Test
    fun aSubscriptionIsNamedByThePodcastGuidOfItsFeed() {
        // The example of the Podcast Namespace.
        assertEquals("9b024349-ccf0-5f69-a609-6b82873eab3c", podcastGuid("podnews.net/rss"))
        assertEquals(podcastGuid("podnews.net/rss"), podcastGuid("https://podnews.net/rss/"))
    }

    @Test
    fun subscriptionsAreListedPageByPage() = testApplication {
        podcastServer()
        val token = client.token()

        val first = client.opa(token, "/v1/subscriptions?per_page=3").json()
        assertEquals(4, first["total"]!!.jsonPrimitive.int)
        assertEquals("/v1/subscriptions?page=2&per_page=3", first["next"]!!.jsonPrimitive.content)
        val entry = first["subscriptions"]!!.jsonArray.first().jsonObject
        assertEquals(podcastGuid(entry["feed_url"]!!.jsonPrimitive.content), entry["guid"]!!.jsonPrimitive.content)
        assertTrue(entry["is_subscribed"]!!.jsonPrimitive.boolean)

        val second = client.opa(token, "/v1/subscriptions?page=2&per_page=3").json()
        assertEquals(1, second["subscriptions"]!!.jsonArray.size)
        assertEquals("/v1/subscriptions?page=1&per_page=3", second["previous"]!!.jsonPrimitive.content)
        assertNull(second["next"])

        // Basic with an app password works too; nothing does not.
        val basic = "Basic " + Base64.getEncoder().encodeToString("${TestSeed.ALICE}:$token".toByteArray())
        assertEquals(HttpStatusCode.OK, client.get("/v1/subscriptions") { header(HttpHeaders.Authorization, basic) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/subscriptions").status)
    }

    @Test
    fun clientsAddUpdateAndDeleteSubscriptions() = testApplication {
        val services = podcastServer()
        val token = client.token()
        val alice = services.accounts.userByUsername(TestSeed.ALICE)!!

        val added = client.post("/v1/subscriptions") {
            bearerAuth(token)
            setBody(
                """{"subscriptions":[{"feed_url":"https://feeds.test/podcast20.xml","guid":"2d8bb39b-8d34-48d4-b223-a0d01eb27d71"},""" +
                    """{"feed_url":"example.com/rss"},{"feed_url":"ftp://example.com/rss"}]}""",
            )
        }.json()
        val success = added["success"]!!.jsonArray.single().jsonObject
        assertEquals("2d8bb39b-8d34-48d4-b223-a0d01eb27d71", success["guid"]!!.jsonPrimitive.content)
        assertEquals(listOf("No protocol present", "Only http and https feeds are supported"), added["failure"]!!.jsonArray.map { it.jsonObject["message"]!!.jsonPrimitive.content })
        assertTrue("https://feeds.test/podcast20.xml" in UserCatalog(services.database, alice.id).subscribedFeedUrls())

        // Unsubscribed: still an entry, and every client knows it (gPodder too).
        val cac = podcastGuid("https://feeds.test/cac.xml")
        val patched = client.patch("/v1/subscriptions/$cac") {
            bearerAuth(token)
            setBody("""{"is_subscribed":false}""")
        }.json()
        assertEquals(false, patched["is_subscribed"]!!.jsonPrimitive.boolean)
        assertTrue("https://feeds.test/cac.xml" !in UserCatalog(services.database, alice.id).subscribedFeedUrls())
        assertEquals(false, client.opa(token, "/v1/subscriptions/$cac").json()["is_subscribed"]!!.jsonPrimitive.boolean)
        val removed = client.get("/api/2/subscriptions/alice/phone.json?since=1") { bearerAuth(token) }.json()["remove"]!!.jsonArray
        assertTrue(removed.any { it.jsonPrimitive.content == "https://feeds.test/cac.xml" })

        // Deleted: 202, then 410, and the deletion's status.
        val tk = podcastGuid("https://feeds.test/tk.xml")
        val deletion = client.delete("/v1/subscriptions/$tk") { bearerAuth(token) }
        assertEquals(HttpStatusCode.Accepted, deletion.status)
        val id = deletion.json()["deletion_id"]!!.jsonPrimitive.long
        assertEquals(HttpStatusCode.Gone, client.opa(token, "/v1/subscriptions/$tk").status)
        assertEquals("SUCCESS", client.opa(token, "/v1/deletions/$id").json()["status"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.NotFound, client.opa(token, "/v1/subscriptions/00000000-0000-0000-0000-000000000000").status)
        // Not listed any more; listed as deleted since a date.
        val listed = client.opa(token, "/v1/subscriptions").json()["subscriptions"]!!.jsonArray.map { it.jsonObject["guid"]!!.jsonPrimitive.content }
        assertTrue(tk !in listed)
        val since = client.opa(token, "/v1/subscriptions?since=2000-01-01T00:00:00Z").json()["subscriptions"]!!.jsonArray.map { it.jsonObject }
        assertTrue(since.single { it["guid"]!!.jsonPrimitive.content == tk }["deleted"] != null)
    }
}
