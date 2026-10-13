package fr.podcast.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import fr.podcast.server.db.ServerDatabase
import fr.podcast.server.dev.DevSeed
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
                "TRUNCATE users, api_tokens, http_sessions, podcasts, episodes, subscriptions, playback_states RESTART IDENTITY CASCADE",
            )
        }
        runBlocking { DevSeed.seed(database) }
    }
}

val testConfig = ServerConfig(name = "Podcast (test)", secureCookies = false)

val HalForms = ContentType("application", "prs.hal-forms+json")

fun ApplicationTestBuilder.podcastServer() {
    application { module(testConfig, TestDatabase.database) }
}

suspend fun HttpClient.token(username: String = DevSeed.ALICE, password: String = DevSeed.ALICE_PASSWORD): String {
    val response = post("/auth/tokens") {
        contentType(ContentType.Application.Json)
        setBody("""{"username":"$username","password":"$password","deviceName":"test"}""")
    }
    return response.json()["token"]!!.jsonPrimitive.content
}

suspend fun HttpClient.getJson(path: String, token: String, accept: ContentType = HalForms): JsonObject =
    get(path) { bearerAuth(token); accept(accept) }.json()

suspend fun io.ktor.client.statement.HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject
