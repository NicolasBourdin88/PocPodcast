package fr.podcast.server

import fr.podcast.server.api.apiRoutes
import fr.podcast.server.api.subscriptionRoutes
import fr.podcast.server.auth.Accounts
import fr.podcast.server.auth.installAuthentication
import fr.podcast.server.db.ServerDatabase
import fr.podcast.server.feeds.FeedFetcher
import fr.podcast.server.feeds.FeedRefresher
import fr.podcast.server.feeds.UrlGuard
import fr.podcast.server.web.webRoutes
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

data class ServerConfig(
    /** Name shown to clients (root resource), useful when a client knows several servers. */
    val name: String,
    val port: Int = 8080,
    val host: String = "0.0.0.0",
    /** Cookies only sent over HTTPS: true in production. */
    val secureCookies: Boolean = true,
    /** Feeds on private networks are refused (SSRF, see UrlGuard) unless this is set. */
    val allowPrivateFeeds: Boolean = false,
    /** Periodic jobs (feed refresh, downloads, purge); off in tests, which call them directly. */
    val backgroundJobs: Boolean = true,
    /** How long a feed stays unread before the server reads it again. */
    val refreshInterval: Duration = 1.hours,
) {
    companion object {
        fun fromEnvironment(defaultName: String, defaultSecureCookies: Boolean) = ServerConfig(
            name = System.getenv("SERVER_NAME") ?: defaultName,
            port = System.getenv("PORT")?.toIntOrNull() ?: 8080,
            secureCookies = System.getenv("SECURE_COOKIES")?.toBooleanStrictOrNull() ?: defaultSecureCookies,
            allowPrivateFeeds = System.getenv("ALLOW_PRIVATE_FEEDS")?.toBooleanStrictOrNull() ?: false,
            refreshInterval = System.getenv("REFRESH_INTERVAL_MINUTES")?.toLongOrNull()?.minutes ?: 1.hours,
        )
    }
}

/** Calls to other servers (feeds, search, audio files). Redirects are followed by hand, see UrlGuard. */
fun outgoingHttpClient(): HttpClient = HttpClient(CIO) {
    followRedirects = false
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        requestTimeoutMillis = 60_000
    }
}

/** The server's services, built once and shared by the routes and the background jobs. */
class ServerServices(
    val config: ServerConfig,
    val database: ServerDatabase,
    val outgoing: HttpClient,
    /** Lives as long as the application: background work started by requests. */
    private val scope: CoroutineScope,
) {
    val accounts = Accounts(database)
    val guard = UrlGuard(config.allowPrivateFeeds)
    val refresher = FeedRefresher(database, FeedFetcher(outgoing, guard))

    /** Reads new feeds (e.g. after an OPML import) without making the request wait. */
    fun refreshSoon(podcastIds: List<Long>) {
        if (podcastIds.isEmpty() || !config.backgroundJobs) return
        scope.launch {
            val permits = Semaphore(4)
            podcastIds.map { id -> launch { permits.withPermit { refresher.refresh(id) } } }
        }
    }

    /** A subscription changed (e.g. automatic download turned on). */
    @Suppress("UnusedParameter")
    suspend fun onSubscriptionChanged(podcastId: Long) = Unit

    /** Removes a podcast nobody follows any more, with its episodes. */
    suspend fun removePodcast(podcastId: Long) {
        database.query { catalogQueries.deletePodcast(podcastId) }
    }
}

/** Production entry point: a real PostgreSQL is required. For development use `./gradlew :server:run`. */
fun main() {
    val url = System.getenv("DATABASE_URL")
    if (url == null) {
        System.err.println("DATABASE_URL manquant (ex. jdbc:postgresql://localhost:5432/podcast). En développement : ./gradlew :server:run")
        exitProcess(1)
    }
    val database = ServerDatabase.connect(url, System.getenv("DATABASE_USER") ?: "podcast", System.getenv("DATABASE_PASSWORD"))
    startServer(ServerConfig.fromEnvironment(defaultName = "Podcast", defaultSecureCookies = true), database)
}

fun startServer(config: ServerConfig, database: ServerDatabase) {
    embeddedServer(Netty, port = config.port, host = config.host) { module(config, database) }.start(wait = true)
}

fun Application.module(config: ServerConfig, database: ServerDatabase, outgoing: HttpClient = outgoingHttpClient()) {
    val services = ServerServices(config, database, outgoing, scope = this)
    installAuthentication(services.accounts, database, config.secureCookies)
    // Every resource is negotiated on these two headers (docs/api/conventions.md).
    install(createApplicationPlugin("Vary") { onCall { it.response.headers.append(HttpHeaders.Vary, "Accept, Accept-Language") } })

    routing {
        apiRoutes(services)
        subscriptionRoutes(services)
        webRoutes(database, services.accounts)
        swaggerUI(path = "docs", swaggerFile = "openapi/podcast-api.yaml")
    }

    if (config.backgroundJobs) startBackgroundJobs(services)
    monitor.subscribe(ApplicationStopped) { outgoing.close() }
}

/** Reads the followed feeds again every [ServerConfig.refreshInterval]. */
private fun Application.startBackgroundJobs(services: ServerServices) {
    val log = LoggerFactory.getLogger("fr.podcast.server.jobs")
    launch {
        while (isActive) {
            try {
                services.refresher.refreshDue(services.config.refreshInterval)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                log.warn("Feed refresh failed", failure)
            }
            delay(JOBS_PERIOD)
        }
    }
}

private val JOBS_PERIOD = 5.minutes
