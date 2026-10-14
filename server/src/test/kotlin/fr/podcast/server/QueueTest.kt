package fr.podcast.server

import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.writeBytes
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

/** The queue ("up next", ADR 0008). Seed: episodes 1, 5, 9 are Talking Kotlin; 2, 6 Les Cast Codeurs. */
class QueueTest {

    @BeforeTest
    fun reset() = TestDatabase.reset()

    private suspend fun HttpClient.queue(token: String): List<String> =
        getJson("/queue", token)["_embedded"]!!.jsonObject["episodes"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

    private suspend fun HttpClient.enqueue(token: String, episodeId: String, position: Int? = null): HttpResponse = post("/queue") {
        bearerAuth(token)
        accept(HalForms)
        contentType(ContentType.Application.Json)
        setBody(buildJsonObject { put("episodeId", episodeId); position?.let { put("position", it) } }.toString())
    }

    private suspend fun HttpClient.move(token: String, episodeId: String, body: String): HttpResponse = put("/queue/$episodeId") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    @Test
    fun theQueueKeepsTheListeningOrder() = testApplication {
        podcastServer()
        val alice = client.token()
        val bob = client.token(TestSeed.BOB, TestSeed.BOB_PASSWORD)

        for (episode in listOf("1", "3", "6")) assertEquals(HttpStatusCode.OK, client.enqueue(alice, episode).status)
        assertEquals(listOf("1", "3", "6"), client.queue(alice))
        client.enqueue(alice, "8", position = 0) // play next
        assertEquals(listOf("8", "1", "3", "6"), client.queue(alice))
        client.enqueue(alice, "6", position = 1) // already queued: it moves
        assertEquals(listOf("8", "6", "1", "3"), client.queue(alice))
        assertEquals(HttpStatusCode.OK, client.move(alice, "8", """{"position":99}""").status) // beyond the end: last
        assertEquals(listOf("6", "1", "3", "8"), client.queue(alice))
        assertEquals(HttpStatusCode.NoContent, client.delete("/queue/1") { bearerAuth(alice) }.status)
        assertEquals(listOf("6", "3", "8"), client.queue(alice))
        assertEquals(HttpStatusCode.NotFound, client.delete("/queue/1") { bearerAuth(alice) }.status)
        assertEquals(emptyList(), client.queue(bob)) // one queue per user

        // Episodes say whether they are queued, and offer the matching actions.
        val queued = client.getJson("/episodes/6", alice)
        assertTrue(queued["inQueue"]!!.jsonPrimitive.boolean)
        assertEquals(setOf("dequeue", "move"), queued["_templates"]!!.jsonObject.keys.filter { "queue" in it || it == "move" }.toSet())
        val other = client.getJson("/episodes/2", alice)
        assertFalse(other["inQueue"]!!.jsonPrimitive.boolean)
        val enqueue = other["_templates"]!!.jsonObject["enqueue"]!!.jsonObject
        val episodeId = enqueue["properties"]!!.jsonArray.first { it.jsonObject["name"]!!.jsonPrimitive.content == "episodeId" }.jsonObject
        assertEquals("2", episodeId["value"]!!.jsonPrimitive.content)
        assertTrue(episodeId["readOnly"]!!.jsonPrimitive.boolean)

        assertEquals(HttpStatusCode.NoContent, client.delete("/queue") { bearerAuth(alice) }.status)
        val empty = client.getJson("/queue", alice)
        assertEquals(0, empty["count"]!!.jsonPrimitive.content.toInt())
        assertFalse("_templates" in empty) // nothing to clear
    }

    @Test
    fun playedEpisodesAndUnsubscribedPodcastsLeaveTheQueue() = testApplication {
        podcastServer()
        val alice = client.token()
        for (episode in listOf("1", "2", "6", "9")) client.enqueue(alice, episode)

        client.post("/episodes/2/played") { bearerAuth(alice) }
        assertEquals(listOf("1", "6", "9"), client.queue(alice))

        client.delete("/subscriptions/1") { bearerAuth(alice) } // Les Cast Codeurs
        assertEquals(listOf("1", "9"), client.queue(alice))
        client.move(alice, "9", """{"position":0}""") // positions are dense again
        assertEquals(listOf("9", "1"), client.queue(alice))
    }

    @Test
    fun queuedEpisodesAreNotPurged() = testApplication {
        val services = podcastServer()
        val bob = client.token(TestSeed.BOB, TestSeed.BOB_PASSWORD)
        val file = TestDatabase.mediaDirectory.resolve("4/7.mp3")
        Files.createDirectories(file.parent)
        file.writeBytes(ByteArray(1_000))
        TestDatabase.execute(
            "INSERT INTO media_files(episode_id, status, storage_path, size_bytes, mime_type, downloaded_at) " +
                "VALUES (7, 'READY', '4/7.mp3', 1000, 'audio/mpeg', now() - interval '40 days')",
        )

        client.enqueue(bob, "7")
        assertEquals(0, runBlocking { services.media.purge(retention = 30.days, maxBytes = null) })
        assertTrue(file.exists())

        client.delete("/queue/7") { bearerAuth(bob) }
        assertEquals(1, runBlocking { services.media.purge(retention = 30.days, maxBytes = null) })
        assertFalse(file.exists())
    }

    @Test
    fun invalidRequestsAreExplained() = testApplication {
        podcastServer()
        val alice = client.token()

        suspend fun field(response: HttpResponse): String {
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            return response.json()["errors"]!!.jsonArray.single().jsonObject["field"]!!.jsonPrimitive.content
        }
        assertEquals("episodeId", field(client.post("/queue") { bearerAuth(alice); contentType(ContentType.Application.Json); setBody("{}") }))
        assertEquals("episodeId", field(client.enqueue(alice, "999")))
        assertEquals("position", field(client.enqueue(alice, "1", position = -1)))
        assertEquals(HttpStatusCode.NotFound, client.move(alice, "1", """{"position":0}""").status) // not queued
        client.enqueue(alice, "1")
        assertEquals("position", field(client.move(alice, "1", """{"position":"first"}""")))
    }
}
