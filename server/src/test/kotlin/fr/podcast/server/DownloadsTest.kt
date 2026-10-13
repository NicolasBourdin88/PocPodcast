package fr.podcast.server

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.writeBytes
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

class DownloadsTest {

    @BeforeTest
    fun reset() = TestDatabase.reset()

    private fun mediaFile(podcastId: Int, episodeId: Int) = TestDatabase.mediaDirectory.resolve("$podcastId/$episodeId.mp3")

    @Test
    fun theServerKeepsOneCopyForEveryone() = testApplication {
        val services = podcastServer()
        val alice = client.token()
        val bob = client.token(TestSeed.BOB, TestSeed.BOB_PASSWORD)

        assertEquals("NONE", client.getJson("/episodes/1", alice)["fileStatus"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.Accepted, client.post("/episodes/1/download") { bearerAuth(alice) }.status)
        assertEquals("PENDING", client.getJson("/episodes/1", bob)["fileStatus"]!!.jsonPrimitive.content)

        assertTrue(services.media.downloadNext())
        assertFalse(services.media.downloadNext()) // nothing left

        val episode = client.getJson("/episodes/1", bob)
        assertEquals("READY", episode["fileStatus"]!!.jsonPrimitive.content)
        assertFalse("download" in episode["_templates"]!!.jsonObject)
        assertTrue(mediaFile(2, 1).exists()) // Talking Kotlin, episode 1

        // Served by the server, with byte ranges for seeking.
        val whole = client.get("/episodes/1/audio") { bearerAuth(bob) }
        assertEquals(HttpStatusCode.OK, whole.status)
        assertEquals(ContentType.parse("audio/mpeg"), whole.contentType()?.withoutParameters())
        assertContentEquals(testAudio, whole.readRawBytes())
        val part = client.get("/episodes/1/audio") {
            bearerAuth(bob)
            header(HttpHeaders.Range, "bytes=1000-1099")
        }
        assertEquals(HttpStatusCode.PartialContent, part.status)
        assertContentEquals(testAudio.copyOfRange(1000, 1100), part.readRawBytes())
    }

    @Test
    fun failedDownloadsCanBeRetried() = testApplication {
        val services = podcastServer()
        val token = client.token()

        client.post("/episodes/2/download") { bearerAuth(token) } // the fake CDN does not have it
        services.media.downloadNext()
        val failed = client.getJson("/episodes/2", token)
        assertEquals("FAILED", failed["fileStatus"]!!.jsonPrimitive.content)
        assertTrue("download" in failed["_templates"]!!.jsonObject)

        client.post("/episodes/2/download") { bearerAuth(token) }
        assertEquals("PENDING", client.getJson("/episodes/2", token)["fileStatus"]!!.jsonPrimitive.content)
        // Still the original file until the server has its copy.
        val noRedirect = createClient { followRedirects = false }
        assertEquals(HttpStatusCode.Found, noRedirect.get("/episodes/2/audio") { bearerAuth(token) }.status)
    }

    @Test
    fun automaticDownloadTakesTheNewestEpisodes() = testApplication {
        val services = podcastServer()
        val token = client.token()

        // Alice's subscription 2 is Talking Kotlin, whose newest episode is episode 1.
        client.patch("/subscriptions/2") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"autoDownload":true}""")
        }

        assertEquals("PENDING", client.getJson("/episodes/1", token)["fileStatus"]!!.jsonPrimitive.content)
        assertEquals("NONE", client.getJson("/episodes/5", token)["fileStatus"]!!.jsonPrimitive.content) // older one
        services.media.downloadNext()
        assertEquals("READY", client.getJson("/episodes/1", token)["fileStatus"]!!.jsonPrimitive.content)
    }

    @Test
    fun purgeSparesKeptAndRecentlyPlayedEpisodes() = testApplication {
        val services = podcastServer()
        val token = client.token()
        // Five files downloaded 40 days ago (episode to podcast, see TestSeed).
        val owners = mapOf(6 to 1, 7 to 4, 2 to 1, 4 to 3, 3 to 4)
        for ((episode, podcast) in owners) downloaded(episode, podcast, daysAgo = 40)
        client.put("/episodes/3/kept") { bearerAuth(token) } // kept by Alice
        // Alice is listening to episodes 2 and 4 (seed): 4 recently, 2 abandoned long ago.
        TestDatabase.execute("UPDATE playback_states SET updated_at = now() - interval '60 days' WHERE episode_id = 2")

        assertEquals(3, runBlocking { services.media.purge(retention = 30.days, maxBytes = null) })
        listOf(6 to 1, 7 to 4, 2 to 1).forEach { (episode, podcast) -> assertFalse(mediaFile(podcast, episode).exists(), "$episode") }
        assertTrue(mediaFile(3, 4).exists()) // being listened to
        assertTrue(mediaFile(4, 3).exists()) // kept
        assertEquals("NONE", client.getJson("/episodes/6", token)["fileStatus"]!!.jsonPrimitive.content)

        // Above the quota, recent files go too (oldest first), still sparing protected ones.
        downloaded(9, 2, daysAgo = 0, size = 5_000)
        assertEquals(1, runBlocking { services.media.purge(retention = 30.days, maxBytes = 1_000) })
        assertFalse(mediaFile(2, 9).exists())
        assertTrue(mediaFile(4, 3).exists())
    }

    private fun downloaded(episode: Int, podcast: Int, daysAgo: Int, size: Int = 1_000) {
        Files.createDirectories(mediaFile(podcast, episode).parent)
        mediaFile(podcast, episode).writeBytes(ByteArray(size))
        TestDatabase.execute(
            "INSERT INTO media_files(episode_id, status, storage_path, size_bytes, mime_type, downloaded_at) " +
                "VALUES ($episode, 'READY', '$podcast/$episode.mp3', $size, 'audio/mpeg', now() - interval '$daysAgo days')",
        )
    }

    @Test
    fun filesGoWithTheirPodcast() = testApplication {
        val services = podcastServer()
        val alice = client.token()
        val bob = client.token(TestSeed.BOB, TestSeed.BOB_PASSWORD)
        client.post("/episodes/1/download") { bearerAuth(alice) }
        services.media.downloadNext()
        assertTrue(mediaFile(2, 1).exists())

        // Talking Kotlin: Alice's subscription 2 and Bob's 5.
        client.delete("/subscriptions/2", alice)
        client.delete("/subscriptions/5", bob)

        assertFalse(mediaFile(2, 1).exists())
    }

    private suspend fun io.ktor.client.HttpClient.delete(path: String, token: String) =
        request(path) {
            method = io.ktor.http.HttpMethod.Delete
            bearerAuth(token)
        }
}
