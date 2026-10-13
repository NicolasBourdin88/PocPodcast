package fr.podcast.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import fr.podcast.server.db.ServerDatabase
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.ktor.client.HttpClient
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

    /** Fresh demo data with predictable ids (users 1 = alice, 2 = bob; episodes 1…9, most recent first). */
    fun reset() {
        dataSource.connection.use {
            it.createStatement().execute(
                "TRUNCATE users, api_tokens, http_sessions, podcasts, episodes, episode_transcripts, subscriptions, " +
                    "playback_states, media_files RESTART IDENTITY CASCADE",
            )
        }
        runBlocking { TestSeed.seed(database) }
    }
}

// Background jobs off (tests call them directly); test feeds live on a fake host.
val testConfig = ServerConfig(name = "Podcast (test)", secureCookies = false, allowPrivateFeeds = true, backgroundJobs = false)

/**
 * The outside world seen by the server in tests: https://feeds.test serves the fixtures of
 * src/test/resources/feeds, everything else is unknown.
 */
fun testFeeds(): HttpClient = HttpClient(
    MockEngine { request ->
        val fixture = request.url.encodedPath.removePrefix("/")
        val content = TestDatabase::class.java.getResourceAsStream("/feeds/$fixture")?.readBytes()
        when {
            request.url.host != "feeds.test" || content == null -> respond("", HttpStatusCode.NotFound)
            fixture.endsWith(".html") -> respond(ByteReadChannel(content), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
            else -> respond(ByteReadChannel(content), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/rss+xml"))
        }
    },
) { followRedirects = false }

val HalForms = ContentType("application", "prs.hal-forms+json")

fun ApplicationTestBuilder.podcastServer() {
    application { module(testConfig, TestDatabase.database, testFeeds()) }
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
