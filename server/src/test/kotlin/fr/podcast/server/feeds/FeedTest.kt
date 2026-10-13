package fr.podcast.server.feeds

import fr.podcast.shared.model.Transcript
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

class FeedTest {

    private fun fixture(name: String) = javaClass.getResourceAsStream("/feeds/$name")!!

    @Test
    fun rssWithItunesAndPodcast20IsRead() {
        val feed = RssParser.parse(fixture("podcast20.xml"))

        assertEquals("Le Podcast de Test", feed.title)
        assertEquals("Équipe Test", feed.author)
        assertEquals("Un podcast de test & d'essai.", feed.description)
        assertEquals("https://test.example.org/cover.jpg", feed.artworkUrl)
        assertEquals("https://test.example.org", feed.websiteUrl)
        assertEquals("fr", feed.language)
        // The item without audio is not an episode.
        assertEquals(listOf("test-ep-3", "https://cdn.example.org/ep2.mp3", "test-ep-1"), feed.items.map { it.guid })

        val third = feed.items[0]
        assertEquals(Instant.parse("2026-10-12T06:00:00Z"), third.publishedAt)
        assertEquals(3930, third.durationSeconds)
        assertEquals("Chapitres et transcriptions.\nDeuxième paragraphe.", third.description)
        assertEquals(31_457_280L, third.audioLength)
        assertEquals("https://test.example.org/ep3/chapters.json", third.chaptersUrl)
        assertEquals("application/json+chapters", third.chaptersType)
        assertEquals(
            listOf(
                Transcript("https://test.example.org/ep3/transcript.vtt", "text/vtt", "fr"),
                Transcript("https://test.example.org/ep3/transcript.srt", "application/x-subrip"),
            ),
            third.transcripts,
        )

        val second = feed.items[1]
        assertEquals(Instant.parse("2026-10-03T16:30:00Z"), second.publishedAt) // PDT = UTC-7
        assertEquals(2730, second.durationSeconds)
        assertEquals("Sans guid : l'URL du fichier sert d'identifiant.", second.description)

        val first = feed.items[2]
        assertNull(first.publishedAt) // unreadable date
        assertEquals(2700, first.durationSeconds)
        assertNull(first.audioLength)
    }

    @Test
    fun otherDocumentsAreRejected() {
        assertFailsWith<FeedException.NotAFeed> { RssParser.parse(fixture("not-a-feed.html")) }
        assertFailsWith<FeedException.NotAFeed> { RssParser.parse(fixture("atom.xml")) }
        // External entities are never resolved (XXE).
        assertFailsWith<FeedException.NotAFeed> { RssParser.parse(fixture("xxe.xml")) }
    }

    @Test
    fun durationsAndDatesFromRealFeeds() {
        assertEquals(3600, RssParser.parseDuration("1:00:00"))
        assertEquals(330, RssParser.parseDuration("05:30"))
        assertEquals(90, RssParser.parseDuration("90.4"))
        assertEquals(0, RssParser.parseDuration("about an hour"))
        assertEquals(Instant.parse("2026-10-05T04:30:00Z"), RssParser.parseDate("Mon, 05 Oct 2026 04:30:00 +0000"))
        assertEquals(Instant.parse("2026-10-05T04:30:00Z"), RssParser.parseDate("5 OCT 2026 06:30 +0200"))
        assertEquals(Instant.parse("2026-10-05T04:30:00Z"), RssParser.parseDate("2026-10-05T04:30:00Z"))
    }

    @Test
    fun privateAddressesAreRefused() {
        fun guard(vararg ip: String) = UrlGuard(allowPrivateNetworks = false) { ip.map(InetAddress::getByName) }

        guard("93.184.216.34").check("https://feeds.example.org/rss")
        listOf("127.0.0.1", "10.1.2.3", "192.168.1.10", "172.16.0.1", "169.254.169.254", "100.64.0.1", "::1", "fd00::1").forEach { ip ->
            assertFailsWith<FeedException.ForbiddenAddress>(ip) { guard(ip).check("https://feeds.example.org/rss") }
        }
        assertFailsWith<FeedException.InvalidUrl> { guard("93.184.216.34").check("file:///etc/passwd") }
        assertFailsWith<FeedException.InvalidUrl> { guard("93.184.216.34").check("not a url") }
        // Development servers may allow local feeds.
        UrlGuard(allowPrivateNetworks = true).check("http://localhost:9000/feed.xml")
    }

    @Test
    fun fetcherFollowsRedirectsSafelyAndUsesConditionalRequests() = runBlocking<Unit> {
        val publicGuard = UrlGuard(allowPrivateNetworks = false) { host ->
            listOf(InetAddress.getByName(if (host == "internal.example.org") "10.0.0.5" else "93.184.216.34"))
        }
        val requests = mutableListOf<String>()
        val client = HttpClient(
            MockEngine { request ->
                requests += "${request.url.host}${request.url.encodedPath} ${request.headers[HttpHeaders.IfNoneMatch]}"
                when (request.url.encodedPath) {
                    "/old" -> respond("", HttpStatusCode.MovedPermanently, headersOf(HttpHeaders.Location, "/new"))
                    "/new" -> respond(
                        ByteReadChannel(fixture("podcast20.xml").readBytes()), HttpStatusCode.OK,
                        headersOf(HttpHeaders.ETag, "\"v1\""),
                    )
                    "/cached" -> respond("", HttpStatusCode.NotModified)
                    "/to-internal" -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "http://internal.example.org/admin"))
                    "/page" -> respond("<html>Hi</html>", HttpStatusCode.OK)
                    else -> respond("", HttpStatusCode.NotFound)
                }
            },
        ) { followRedirects = false }
        val fetcher = FeedFetcher(client, publicGuard)

        val moved = assertIs<FetchResult.Fetched>(fetcher.fetch("https://feeds.example.org/old"))
        assertEquals("https://feeds.example.org/new", moved.movedTo)
        assertEquals("\"v1\"", moved.etag)
        assertEquals(3, moved.feed.items.size)

        assertIs<FetchResult.NotModified>(fetcher.fetch("https://feeds.example.org/cached", etag = "\"v1\""))
        assertEquals("feeds.example.org/cached \"v1\"", requests.last())

        assertFailsWith<FeedException.ForbiddenAddress> { fetcher.fetch("https://feeds.example.org/to-internal") }
        assertFailsWith<FeedException.NotAFeed> { fetcher.fetch("https://feeds.example.org/page") }
        assertFailsWith<FeedException.Unreachable> { fetcher.fetch("https://feeds.example.org/missing") }
        assertFailsWith<FeedException.TooLarge> { FeedFetcher(client, publicGuard, maxBytes = 100).fetch("https://feeds.example.org/new") }
    }
}
