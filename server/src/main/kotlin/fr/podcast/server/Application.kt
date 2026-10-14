package fr.podcast.server

import fr.podcast.server.api.apiRoutes
import fr.podcast.server.api.queueRoutes
import fr.podcast.server.api.searchRoutes
import fr.podcast.server.api.subscriptionRoutes
import fr.podcast.server.auth.Accounts
import fr.podcast.server.auth.installAuthentication
import fr.podcast.server.db.ServerDatabase
import fr.podcast.server.feeds.FeedFetcher
import fr.podcast.server.feeds.FeedRefresher
import fr.podcast.server.feeds.UrlGuard
import fr.podcast.server.media.AudioLinks
import fr.podcast.server.media.MediaService
import fr.podcast.server.media.MediaStore
import fr.podcast.server.search.AppleSource
import fr.podcast.server.search.PodcastIndexSource
import fr.podcast.server.search.PodcastSearch
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
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
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
    /** Podcast Index keys (free at https://api.podcastindex.org); search uses Apple Podcasts only without them. */
    val podcastIndexKey: String? = null,
    val podcastIndexSecret: String? = null,
    /** Where downloaded audio files are stored (shared by every user). */
    val mediaDirectory: Path = Path.of("media"),
    /** Files are purged this long after their download, unless kept or being listened to. */
    val mediaRetention: Duration = 30.days,
    /** Optional disk quota: the oldest purgeable files go first when it is exceeded. */
    val mediaMaxBytes: Long? = null,
    val maxFileBytes: Long = 1L shl 30,
    /** Newest episodes downloaded for podcasts followed with automatic download. */
    val autoDownloadLatest: Int = 1,
) {
    companion object {
        fun fromEnvironment(defaultName: String, defaultSecureCookies: Boolean) = ServerConfig(
            name = System.getenv("SERVER_NAME") ?: defaultName,
            port = System.getenv("PORT")?.toIntOrNull() ?: 8080,
            secureCookies = System.getenv("SECURE_COOKIES")?.toBooleanStrictOrNull() ?: defaultSecureCookies,
            allowPrivateFeeds = System.getenv("ALLOW_PRIVATE_FEEDS")?.toBooleanStrictOrNull() ?: false,
            refreshInterval = System.getenv("REFRESH_INTERVAL_MINUTES")?.toLongOrNull()?.minutes ?: 1.hours,
            podcastIndexKey = System.getenv("PODCASTINDEX_KEY"),
            podcastIndexSecret = System.getenv("PODCASTINDEX_SECRET"),
            mediaDirectory = Path.of(System.getenv("MEDIA_DIR") ?: "media"),
            mediaRetention = System.getenv("MEDIA_RETENTION_DAYS")?.toLongOrNull()?.days ?: 30.days,
            mediaMaxBytes = System.getenv("MEDIA_MAX_GB")?.toLongOrNull()?.let { it shl 30 },
            autoDownloadLatest = System.getenv("AUTO_DOWNLOAD_LATEST")?.toIntOrNull() ?: 1,
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
    /** Signed audio links (ADR 0008); the key is read, or created, once at startup. */
    val audioLinks: AudioLinks = runBlocking { AudioLinks.load(database) }
    val media = MediaService(database, outgoing, guard, MediaStore(config.mediaDirectory), config.maxFileBytes, config.autoDownloadLatest)
    val refresher = FeedRefresher(database, FeedFetcher(outgoing, guard), onEpisodesStored = media::scheduleAutomatic)
    val search = PodcastSearch(
        listOfNotNull(
            AppleSource(outgoing),
            config.podcastIndexKey?.let { key -> config.podcastIndexSecret?.let { PodcastIndexSource(outgoing, key, it) } },
        ),
    )

    /** Reads new feeds (e.g. after an OPML import) without making the request wait. */
    fun refreshSoon(podcastIds: List<Long>) {
        if (podcastIds.isEmpty() || !config.backgroundJobs) return
        scope.launch {
            val permits = Semaphore(4)
            podcastIds.map { id -> launch { permits.withPermit { refresher.refresh(id) } } }
        }
    }

    /** A subscription changed: automatic download may have been turned on. */
    suspend fun onSubscriptionChanged(podcastId: Long) = media.scheduleAutomatic(podcastId)

    /** Removes a podcast nobody follows any more, with its episodes and their files. */
    suspend fun removePodcast(podcastId: Long) {
        media.deletePodcastFiles(podcastId)
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

fun Application.module(config: ServerConfig, database: ServerDatabase, outgoing: HttpClient = outgoingHttpClient()) =
    module(ServerServices(config, database, outgoing, scope = this))

fun Application.module(services: ServerServices) {
    val config = services.config
    val database = services.database
    installAuthentication(services.accounts, database, config.secureCookies)
    // Audio files: byte ranges (seeking in players) and HEAD requests.
    install(PartialContent)
    install(AutoHeadResponse)
    // Every resource is negotiated on these two headers (docs/api/conventions.md).
    install(createApplicationPlugin("Vary") { onCall { it.response.headers.append(HttpHeaders.Vary, "Accept, Accept-Language") } })

    routing {
        apiRoutes(services)
        subscriptionRoutes(services)
        queueRoutes(services)
        searchRoutes(services)
        webRoutes(database, services.accounts)
        swaggerUI(path = "docs", swaggerFile = "openapi/podcast-api.yaml")
    }

    if (config.backgroundJobs) startBackgroundJobs(services)
    monitor.subscribe(ApplicationStopped) { services.outgoing.close() }
}

/** Feed refresh every few minutes, downloads as soon as requested, purge every hour. */
private fun Application.startBackgroundJobs(services: ServerServices) {
    val log = LoggerFactory.getLogger("fr.podcast.server.jobs")
    launch { services.media.run() }
    launch {
        while (isActive) {
            try {
                services.media.purge(services.config.mediaRetention, services.config.mediaMaxBytes)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                log.warn("Purge failed", failure)
            }
            delay(1.hours)
        }
    }
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
