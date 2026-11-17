package fr.podcast.server

import fr.podcast.server.feeds.RssParser
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Playlists as RSS feeds (ADR 0010), read the way podcast apps read them: without signing in. */
class PlaylistFeedTest {

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

    private fun JsonObject.feed(): String? = this["_links"]!!.jsonObject["feed"]?.jsonObject?.get("href")?.jsonPrimitive?.content

    @Test
    fun aPublishedPlaylistIsAFeedForAnyPodcastApp() = testApplication {
        podcastServer()
        val alice = client.token()
        val app = createClient { followRedirects = false } // a podcast app: no credentials
        val id = client.send(HttpMethod.Post, "/playlists", alice, """{"title":"Pour le train","description":"Les trajets"}""")
            .json()["id"]!!.jsonPrimitive.content
        client.send(HttpMethod.Post, "/playlists/$id/episodes", alice, """{"episodeId":"6"}""")
        client.send(HttpMethod.Post, "/playlists/$id/episodes", alice, """{"episodeId":"2"}""")
        assertNull(client.getJson("/playlists/$id", alice).feed())

        val published = client.send(HttpMethod.Put, "/playlists/$id/feed", alice).json()
        val feed = published.feed()!!
        assertTrue("unpublish-feed" in published["_templates"]!!.jsonObject)

        val response = app.get(feed)
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType("application", "rss+xml"), response.contentType()?.withoutParameters())
        val parsed = RssParser.parse(response.bodyAsText().byteInputStream())
        assertEquals("Pour le train", parsed.title)
        assertEquals("Les trajets", parsed.description)
        assertEquals(listOf("LCC 329 - Rentrée 2026", "LCC 330 - Les nouveautés de Kotlin 2.4"), parsed.items.map { it.title })
        assertEquals(listOf("seed-6", "seed-2"), parsed.items.map { it.guid })
        assertEquals("http://localhost$feed/episodes/6", parsed.items.first().audioUrl)
        assertEquals(88 * 60, parsed.items.first().durationSeconds)

        // Enclosures: the server's copy, or here the publisher's file; nothing outside the playlist.
        val enclosure = app.get("$feed/episodes/6")
        assertEquals(HttpStatusCode.Found, enclosure.status)
        assertEquals("https://cdn.test/podcast/seed-6.mp3", enclosure.headers[HttpHeaders.Location])
        assertEquals(HttpStatusCode.NotFound, app.get("$feed/episodes/1").status)

        // A new address: the old one stops working.
        val republished = client.send(HttpMethod.Put, "/playlists/$id/feed", alice).json().feed()!!
        assertNotEquals(feed, republished)
        assertEquals(HttpStatusCode.NotFound, app.get(feed).status)

        // Only the owner publishes: Bob, even with the playlist shared with him, does not.
        val bob = client.token(TestSeed.BOB, TestSeed.BOB_PASSWORD)
        assertEquals(HttpStatusCode.NotFound, client.send(HttpMethod.Put, "/playlists/$id/feed", bob).status)
        client.send(HttpMethod.Post, "/playlists/$id/shares", alice, """{"userId":"2","permission":"WRITE"}""")
        assertEquals(HttpStatusCode.Forbidden, client.send(HttpMethod.Put, "/playlists/$id/feed", bob).status)
        assertNull(client.getJson("/playlists/$id", bob).feed()) // the address stays the owner's

        assertEquals(HttpStatusCode.NoContent, client.send(HttpMethod.Delete, "/playlists/$id/feed", alice).status)
        assertEquals(HttpStatusCode.NotFound, app.get(republished).status)
        assertNull(client.getJson("/playlists/$id", alice).feed())
    }

    @Test
    fun aDynamicPlaylistsFeedFollowsItsRules() = testApplication {
        podcastServer()
        val alice = client.token()
        val id = client.send(HttpMethod.Post, "/playlists", alice, """{"title":"Kotlin","kind":"DYNAMIC","keywords":"kotlin","unplayedOnly":true}""")
            .json()["id"]!!.jsonPrimitive.content
        val feed = client.send(HttpMethod.Put, "/playlists/$id/feed", alice).json().feed()!!

        val titles = RssParser.parse(client.get(feed).bodyAsText().byteInputStream()).items.map { it.title }
        assertEquals(listOf("Compose Multiplatform for iOS is stable", "LCC 330 - Les nouveautés de Kotlin 2.4", "Kotlin Multiplatform at scale"), titles)
    }
}
