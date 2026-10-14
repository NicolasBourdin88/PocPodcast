package fr.podcast.server

import fr.podcast.server.media.AudioLinks
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class AudioLinksTest {

    private class FixedClock(var now: Instant) : Clock {
        override fun now() = now
    }

    @Test
    fun signedLinksExpireAndCannotBeForged() {
        val clock = FixedClock(Instant.parse("2026-10-14T10:30:00Z"))
        val links = AudioLinks(ByteArray(32) { it.toByte() }, validity = 24.hours, clock = clock)
        val url = Url("https://podcast.test" + links.path("42"))
        val expires = url.parameters["expires"]!!
        val signature = url.parameters["signature"]!!

        // Rounded up to the hour: the same link for a while.
        assertEquals(Instant.parse("2026-10-15T11:00:00Z").epochSeconds.toString(), expires)
        assertEquals(AudioLinks.Check.Valid, links.check("42", expires, signature))
        assertEquals(AudioLinks.Check.Invalid, links.check("43", expires, signature)) // another episode
        assertEquals(AudioLinks.Check.Invalid, links.check("42", (expires.toLong() + 3600).toString(), signature)) // extended by hand
        assertEquals(AudioLinks.Check.Invalid, links.check("42", expires, signature.dropLast(2) + "AA"))
        assertEquals(AudioLinks.Check.Invalid, links.check("42", expires, null))
        assertEquals(AudioLinks.Check.Invalid, AudioLinks(ByteArray(32), clock = clock).check("42", expires, signature)) // another server

        clock.now = Instant.parse("2026-10-15T11:00:01Z")
        assertEquals(AudioLinks.Check.Expired, links.check("42", expires, signature))
    }

    @Test
    fun playersOpenSignedLinksWithoutCredentials() = testApplication {
        podcastServer()
        val token = client.token()
        val player = createClient { followRedirects = false } // no token, like Media3, AVPlayer or <audio>

        val href = client.getJson("/episodes/1", token)["_links"]!!.jsonObject["audio"]!!.jsonObject["href"]!!.jsonPrimitive.content
        assertTrue(href.startsWith("/episodes/1/audio?expires="), href)
        val opened = player.get(href)
        assertEquals(HttpStatusCode.Found, opened.status)
        assertEquals("https://cdn.test/podcast/seed-1.mp3", opened.headers[HttpHeaders.Location])

        assertEquals(HttpStatusCode.Unauthorized, player.get("/episodes/1/audio").status)
        assertEquals(HttpStatusCode.Unauthorized, player.get(href.replace("/episodes/1/", "/episodes/2/")).status)

        val key = runBlocking { TestDatabase.database.query { secretsQueries.secret("audio-links").executeAsOne() } }
        val expired = player.get(AudioLinks(key, validity = (-2).hours).path("1"))
        assertEquals(HttpStatusCode.Forbidden, expired.status)
        assertEquals("/problems/audio-link-expired", expired.json()["type"]!!.jsonPrimitive.content)
    }
}
