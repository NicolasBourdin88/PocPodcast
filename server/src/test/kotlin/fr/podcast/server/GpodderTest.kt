package fr.podcast.server

import fr.podcast.server.catalog.UserCatalog
import fr.podcast.shared.model.EpisodeStatus
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The gPodder API as AntennaPod uses it (ADR 0011, traces in poc/gpodder-sync/traces). */
class GpodderTest {

    @BeforeTest
    fun reset() = TestDatabase.reset()

    private fun basic(user: String, secret: String) = "Basic " + Base64.getEncoder().encodeToString("$user:$secret".toByteArray())

    /** Like AntennaPod: Basic on login.json, then only the cookie. */
    private suspend fun ApplicationTestBuilder.signedIn(user: String, secret: String): HttpClient {
        val client = createClient { install(HttpCookies) }
        val login = client.post("/api/2/auth/$user/login.json") { header(HttpHeaders.Authorization, basic(user, secret)) }
        assertEquals(HttpStatusCode.OK, login.status)
        return client
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    /** gPodder dates: UTC to the second, without zone; a minute ahead so that they are the newest. */
    private fun soon() = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))

    @Test
    fun clientsSignInThenUseTheSessionCookie() = testApplication {
        podcastServer()
        val anonymous = createClient { install(HttpCookies) }

        val wrong = anonymous.post("/api/2/auth/alice/login.json") { header(HttpHeaders.Authorization, basic(TestSeed.ALICE, "wrong")) }
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        assertContains(wrong.headers[HttpHeaders.WWWAuthenticate].orEmpty(), "Basic")
        // Another user's path.
        assertEquals(
            HttpStatusCode.Unauthorized,
            anonymous.post("/api/2/auth/bob/login.json") { header(HttpHeaders.Authorization, basic(TestSeed.ALICE, TestSeed.ALICE_PASSWORD)) }.status,
        )

        val client = signedIn(TestSeed.ALICE, TestSeed.ALICE_PASSWORD)
        client.post("/api/2/devices/alice/antennapod_on_pixel.json") { setBody("""{"caption":"AntennaPod on Pixel","type":"mobile"}""") }
        val device = Json.parseToJsonElement(client.get("/api/2/devices/alice.json").bodyAsText()).jsonArray.single().jsonObject
        assertEquals("antennapod_on_pixel", device["id"]!!.jsonPrimitive.content)
        assertEquals("mobile", device["type"]!!.jsonPrimitive.content)
        assertEquals(4, device["subscriptions"]!!.jsonPrimitive.int)
        // The cookie is Alice's only.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/2/devices/bob.json").status)

        client.post("/api/2/auth/alice/logout.json")
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/2/devices/alice.json").status)
    }

    @Test
    fun anAppPasswordWorksUntilItIsRevoked() = testApplication {
        val services = podcastServer()
        val alice = services.accounts.userByUsername(TestSeed.ALICE)!!
        val appPassword = services.accounts.issueToken(alice, "AntennaPod").value

        val client = signedIn(TestSeed.ALICE, appPassword)
        assertEquals(HttpStatusCode.OK, client.get("/api/2/devices/alice.json").status)
        // Not Bob's.
        assertEquals(
            HttpStatusCode.Unauthorized,
            createClient {}.post("/api/2/auth/bob/login.json") { header(HttpHeaders.Authorization, basic(TestSeed.BOB, appPassword)) }.status,
        )

        val device = services.accounts.devices(alice.id, null).single { it.name == "AntennaPod" }
        services.accounts.revokeDevice(alice.id, device.id)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/2/devices/alice.json").status)
    }

    @Test
    fun subscriptionsAreTheUsersOnEveryDevice() = testApplication {
        val services = podcastServer()
        val client = signedIn(TestSeed.ALICE, TestSeed.ALICE_PASSWORD)

        val first = client.get("/api/2/subscriptions/alice/phone.json?since=0").json()
        assertEquals(4, first["add"]!!.jsonArray.size)
        val since = first["timestamp"]!!.jsonPrimitive.long

        // As AntennaPod sends them; a URL that is not http(s) is rewritten to "" and ignored.
        val upload = client.post("/api/2/subscriptions/alice/phone.json") {
            setBody("""{"add":["https://feeds.test/podcast20.xml","ftp://example.org/feed"],"remove":["https://feeds.test/cac.xml"]}""")
        }.json()
        assertEquals("""[["ftp://example.org/feed",""]]""", upload["update_urls"].toString())
        val aliceId = services.accounts.userByUsername(TestSeed.ALICE)!!.id
        val feeds = UserCatalog(services.database, aliceId).subscribedFeedUrls()
        assertTrue("https://feeds.test/podcast20.xml" in feeds)
        assertTrue("https://feeds.test/cac.xml" !in feeds)

        // Another device gets the changes ("since" is inclusive: a change may come twice).
        val changes = client.get("/api/2/subscriptions/alice/laptop.json?since=$since").json()
        assertTrue("https://feeds.test/podcast20.xml" in changes["add"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("https://feeds.test/cac.xml"), changes["remove"]!!.jsonArray.map { it.jsonPrimitive.content })
        // The device that uploaded is listed.
        assertContains(client.get("/api/2/devices/alice.json").bodyAsText(), "\"id\":\"phone\"")

        val both = client.post("/api/2/subscriptions/alice/phone.json") {
            setBody("""{"add":["https://feeds.test/tk.xml"],"remove":["https://feeds.test/tk.xml"]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, both.status)
    }

    @Test
    fun episodeActionsBecomeTheListeningStateAndBack() = testApplication {
        val services = podcastServer()
        val bobId = services.accounts.userByUsername(TestSeed.BOB)!!.id
        val catalog = UserCatalog(services.database, bobId)
        val client = signedIn(TestSeed.BOB, TestSeed.BOB_PASSWORD)
        val at = soon()

        client.post("/api/2/episodes/bob.json") {
            setBody(
                """[
                {"podcast":"https://feeds.test/frag.xml","episode":"https://cdn.test/podcast/seed-7.mp3","guid":"seed-7","action":"play","timestamp":"$at","started":0,"position":600,"total":2640,"device":"phone"},
                {"podcast":"http://feeds.test/tk.xml/","episode":"https://cdn.test/podcast/seed-1.mp3","action":"play","timestamp":"$at","started":3120,"position":3120,"total":3120},
                {"podcast":"https://feeds.test/frag.xml","episode":"https://cdn.test/podcast/seed-7.mp3","action":"download","timestamp":"$at"}
                ]""",
            )
        }.also { assertEquals(HttpStatusCode.OK, it.status) }
        assertEquals(600, catalog.episode("7")!!.positionSeconds)
        // Found by its audio file, its feed written another way; position = total: played.
        assertEquals(EpisodeStatus.PLAYED, catalog.episode("1")!!.status)

        // An older action loses.
        client.post("/api/2/episodes/bob.json") {
            setBody("""[{"podcast":"https://feeds.test/frag.xml","episode":"https://cdn.test/podcast/seed-7.mp3","guid":"seed-7","action":"play","timestamp":"2020-01-01T00:00:00","started":0,"position":100,"total":2640}]""")
        }
        assertEquals(600, catalog.episode("7")!!.positionSeconds)

        // And back: the state, as play actions (played: position = total).
        val actions = client.get("/api/2/episodes/bob.json?since=0").json()["actions"]!!.jsonArray.map { it.jsonObject }
        val seven = actions.single { it["guid"]?.jsonPrimitive?.content == "seed-7" }
        assertEquals("play", seven["action"]!!.jsonPrimitive.content)
        assertEquals(listOf(0, 600, 2640), listOf("started", "position", "total").map { seven[it]!!.jsonPrimitive.int })
        val played = actions.single { it["guid"]?.jsonPrimitive?.content == "seed-3" }
        assertEquals(played["total"], played["position"])

        // A change in our own clients reaches the gPodder devices.
        val since = client.get("/api/2/episodes/bob.json?since=0").json()["timestamp"]!!.jsonPrimitive.long
        catalog.markPlayed("7")
        val later = client.get("/api/2/episodes/bob.json?since=$since").json()["actions"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2640, later.single { it["guid"]?.jsonPrimitive?.content == "seed-7" }["position"]!!.jsonPrimitive.int)
    }

    @Test
    fun anActionForAFeedNotReadYetWaitsForIt() = testApplication {
        val services = podcastServer()
        val aliceId = services.accounts.userByUsername(TestSeed.ALICE)!!.id
        val client = signedIn(TestSeed.ALICE, TestSeed.ALICE_PASSWORD)
        client.post("/api/2/subscriptions/alice/phone.json") { setBody("""{"add":["https://feeds.test/podcast20.xml"],"remove":[]}""") }
        client.post("/api/2/episodes/alice.json") {
            setBody("""[{"podcast":"https://feeds.test/podcast20.xml","episode":"https://cdn.example.org/ep3.mp3","guid":"test-ep-3","action":"play","timestamp":"${soon()}","started":0,"position":120,"total":3930}]""")
        }

        // Passed on to the other devices meanwhile.
        val waiting = client.get("/api/2/episodes/alice.json?since=0").json()["actions"]!!.jsonArray.map { it.jsonObject }
        assertNotNull(waiting.singleOrNull { it["guid"]?.jsonPrimitive?.content == "test-ep-3" && it["position"]?.jsonPrimitive?.int == 120 })

        // Once the feed is read, the action applies.
        val podcastId = services.database.query { catalogQueries.podcastIdByFeedUrl("https://feeds.test/podcast20.xml").executeAsOne() }
        services.refresher.refresh(podcastId)
        val episodeId = services.database.query { syncQueries.episodeByGuid(podcastId, "test-ep-3").executeAsOne().id }
        val episode = UserCatalog(services.database, aliceId).episode(episodeId.toString())!!
        assertEquals(EpisodeStatus.IN_PROGRESS, episode.status)
        assertEquals(120, episode.positionSeconds)
        // Now told as the state, once.
        val actions = client.get("/api/2/episodes/alice.json?since=0").json()["actions"]!!.jsonArray
        assertEquals(1, actions.count { it.jsonObject["guid"]?.jsonPrimitive?.content == "test-ep-3" })
    }
}
