package fr.podcast.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import fr.podcast.server.db.ServerDatabase
import fr.podcast.server.search.PodcastIndexSource
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One embedded PostgreSQL for the whole test run, reset with the demo data before each test. */
object TestDatabase {
    private val postgres by lazy { EmbeddedPostgres.builder().start() }

    private val dataSource by lazy {
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = postgres.getJdbcUrl("postgres", "postgres")
                username = "postgres"
                maximumPoolSize = 4
            },
        ).also(ServerDatabase::migrate)
    }

    val database by lazy { ServerDatabase(dataSource) }

    /** Downloaded files of the tests, emptied by [reset]. */
    val mediaDirectory: java.nio.file.Path by lazy { java.nio.file.Files.createTempDirectory("podcast-media") }

    /** Raw SQL, to set up situations the API cannot create (old downloads…). */
    fun execute(sql: String) {
        dataSource.connection.use { it.createStatement().execute(sql) }
    }

    /** Fresh demo data with predictable ids (users 1 = alice, 2 = bob; episodes 1…9, most recent first). */
    fun reset() {
        dataSource.connection.use {
            it.createStatement().execute(
                "TRUNCATE users, api_tokens, http_sessions, podcasts, episodes, episode_transcripts, subscriptions, " +
                    "playback_states, media_files, queue_items, shares, playlists, playlist_items, playlist_feeds, subscription_tags, listening_days, " +
                    "gpodder_devices, subscription_changes, episode_actions, subscription_deletions RESTART IDENTITY CASCADE",
            )
        }
        mediaDirectory.toFile().listFiles()?.forEach { it.deleteRecursively() }
        runBlocking { TestSeed.seed(database) }
    }
}

// Background jobs off (tests call them directly); test feeds live on a fake host.
val testConfig = ServerConfig(
    name = "Podcast (test)",
    secureCookies = false,
    allowPrivateFeeds = true,
    backgroundJobs = false,
    podcastIndexKey = "test-key",
    podcastIndexSecret = "test-secret",
    mediaDirectory = TestDatabase.mediaDirectory,
)

/** Bytes of the fake audio file served by https://cdn.test. */
val testAudio = ByteArray(200_000) { (it % 251).toByte() }

/**
 * The artwork served by https://cdn.test/artwork.png: 600 × 400 pixels, its centered square blue,
 * the bands left and right of it red (pixels set one by one: no graphics environment).
 */
val testArtwork: ByteArray = run {
    val image = java.awt.image.BufferedImage(600, 400, java.awt.image.BufferedImage.TYPE_INT_RGB)
    for (x in 0 until 600) for (y in 0 until 400) image.setRGB(x, y, if (x in 100 until 500) 0x0000FF else 0xFF0000)
    java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, "png", it) }.toByteArray()
}

/** How many times the server fetched https://cdn.test/artwork.png. */
val testArtworkRequests = java.util.concurrent.atomic.AtomicInteger()

/**
 * The outside world seen by the server in tests: https://feeds.test serves the fixtures of
 * src/test/resources/feeds, Apple Podcasts and Podcast Index serve src/test/resources/search
 * (the query "panne" breaks both, "demi-panne" only Podcast Index), everything else is unknown.
 */
fun testFeeds(): HttpClient = HttpClient(
    MockEngine { request ->
        val fixture = request.url.encodedPath.removePrefix("/")
        val content = TestDatabase::class.java.getResourceAsStream("/feeds/$fixture")?.readBytes()
        fun search(name: String) = respond(
            ByteReadChannel(TestDatabase::class.java.getResourceAsStream("/search/$name.json")!!.readBytes()),
            HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
        )
        val query = request.url.parameters["term"] ?: request.url.parameters["q"]
        when {
            // Like real podcast hosts: a tracker redirect, then the file.
            request.url.host == "cdn.test" && request.url.encodedPath == "/podcast/seed-1.mp3" ->
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://cdn.test/files/seed-1.mp3"))
            request.url.host == "cdn.test" && request.url.encodedPath == "/files/seed-1.mp3" ->
                respond(ByteReadChannel(testAudio), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "audio/mpeg"))
            request.url.host == "cdn.test" && request.url.encodedPath == "/artwork.png" -> {
                testArtworkRequests.incrementAndGet()
                respond(ByteReadChannel(testArtwork), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
            }
            request.url.host == "cdn.test" && request.url.encodedPath == "/not-an-image.png" ->
                respond("<html>Not found</html>", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
            request.url.host == "itunes.apple.com" ->
                if (query == "panne") respond("", HttpStatusCode.ServiceUnavailable) else search("apple")
            request.url.host == "api.podcastindex.org" -> {
                val date = request.headers["X-Auth-Date"].orEmpty()
                val signed = request.headers["X-Auth-Key"] == "test-key" &&
                    request.headers[HttpHeaders.Authorization] == PodcastIndexSource.sha1Hex("test-key" + "test-secret" + date)
                when {
                    !signed -> respond("", HttpStatusCode.Unauthorized)
                    query == "panne" || query == "demi-panne" -> respond("", HttpStatusCode.InternalServerError)
                    else -> search("podcastindex")
                }
            }
            request.url.host != "feeds.test" || content == null -> respond("", HttpStatusCode.NotFound)
            fixture.endsWith(".html") -> respond(ByteReadChannel(content), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
            else -> respond(ByteReadChannel(content), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/rss+xml"))
        }
    },
) {
    followRedirects = false
    install(HttpTimeout)
}

val HalForms = ContentType("application", "prs.hal-forms+json")

/** Starts the server under test; its services let tests run background jobs on demand. */
fun ApplicationTestBuilder.podcastServer(): ServerServices {
    val services = ServerServices(testConfig, TestDatabase.database, testFeeds(), CoroutineScope(SupervisorJob()))
    application { module(services) }
    return services
}

suspend fun HttpClient.token(username: String = TestSeed.ALICE, password: String = TestSeed.ALICE_PASSWORD): String {
    val response = post("/auth/tokens") {
        contentType(ContentType.Application.Json)
        setBody("""{"username":"$username","password":"$password","deviceName":"test"}""")
    }
    return response.json()["token"]!!.jsonPrimitive.content
}

suspend fun HttpClient.getJson(path: String, token: String, accept: ContentType = HalForms): JsonObject =
    get(path) { bearerAuth(token); accept(accept) }.json()

suspend fun io.ktor.client.statement.HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject
