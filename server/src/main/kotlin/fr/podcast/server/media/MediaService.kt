package fr.podcast.server.media

import fr.podcast.server.db.ServerDatabase
import fr.podcast.server.feeds.FeedException
import fr.podcast.server.feeds.LimitedInputStream
import fr.podcast.server.feeds.SafeHttp
import fr.podcast.server.feeds.UrlGuard
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/** Where downloaded files live: `<root>/<podcast id>/<episode id>.<extension>`. */
class MediaStore(root: Path) {
    val root: Path = root.toAbsolutePath().normalize()

    init {
        Files.createDirectories(this.root)
    }

    /** The file for a stored path, refusing anything outside the root. */
    fun resolve(storagePath: String): Path {
        val path = root.resolve(storagePath).normalize()
        require(path.startsWith(root)) { "$storagePath is outside of the media directory" }
        return path
    }

    fun delete(storagePath: String) {
        Files.deleteIfExists(resolve(storagePath))
    }
}

/**
 * Audio files kept by the server, shared by every user (ADR 0007): explicit and automatic
 * downloads, serving, and the purge that keeps the disk under control.
 */
class MediaService(
    private val database: ServerDatabase,
    client: HttpClient,
    guard: UrlGuard,
    val store: MediaStore,
    private val maxFileBytes: Long,
    /** How many of the newest episodes are downloaded for podcasts followed with automatic download. */
    private val autoDownloadLatest: Int,
) {
    private val log = LoggerFactory.getLogger(MediaService::class.java)
    private val http = SafeHttp(client, guard)
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)

    /** A user asks for the file; a failed download is tried again. */
    suspend fun request(episodeId: Long) {
        database.transaction {
            mediaQueries.requestDownload(episodeId)
            mediaQueries.retryFailedDownload(episodeId)
        }
        wakeUp.trySend(Unit)
    }

    /** The newest episodes of [podcastId], if one of its subscribers asked for automatic downloads. */
    suspend fun scheduleAutomatic(podcastId: Long) {
        val episodes = database.query { mediaQueries.autoDownloadCandidates(podcastId, autoDownloadLatest.toLong()).executeAsList() }
        if (episodes.isEmpty()) return
        database.transaction { episodes.forEach { mediaQueries.requestDownload(it) } }
        wakeUp.trySend(Unit)
    }

    /** The downloaded file and its type, if the episode has one. */
    suspend fun file(episodeId: Long): Pair<Path, String>? {
        val row = database.query { mediaQueries.readyFile(episodeId).executeAsOneOrNull() } ?: return null
        val path = store.resolve(row.storage_path!!)
        return if (Files.isRegularFile(path)) path to (row.mime_type ?: "audio/mpeg") else null
    }

    /** Downloads one pending file; false when there is nothing to do. */
    suspend fun downloadNext(): Boolean {
        val next = database.query { mediaQueries.nextPendingDownload().executeAsOneOrNull() } ?: return false
        if (database.query { mediaQueries.claimDownload(next.episode_id).value } != 1L) return true
        try {
            val stored = download(next.podcast_id, next.episode_id, next.audio_url, next.audio_type)
            database.query { mediaQueries.markDownloaded(stored.path, stored.size, stored.mimeType, next.episode_id) }
            log.info("Episode {} downloaded ({} bytes)", next.episode_id, stored.size)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            log.info("Episode {} not downloaded: {}", next.episode_id, failure.message)
            database.query { mediaQueries.markDownloadFailed(failure.message?.take(500), next.episode_id) }
        }
        return true
    }

    /** Background worker: downloads pending files, then waits for a new request (or a minute). */
    suspend fun run() {
        database.query { mediaQueries.resetInterruptedDownloads() }
        while (currentCoroutineContext().isActive) {
            if (!downloadNext()) withTimeoutOrNull(1.minutes) { wakeUp.receive() }
        }
    }

    /**
     * Deletes the files nobody needs any more, oldest first: those downloaded more than [retention]
     * ago, and then the oldest ones while the total is above [maxBytes]. Kept episodes and episodes
     * someone listened to recently are never deleted.
     */
    suspend fun purge(retention: Duration, maxBytes: Long?): Int {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val expired = now.minus(retention.toJavaDuration())
        val candidates = database.query { mediaQueries.purgeCandidates(activeSince = expired).executeAsList() }
        var total = database.query { mediaQueries.totalReadyBytes().executeAsOne() }
        var purged = 0
        for (candidate in candidates) {
            val tooOld = candidate.downloaded_at?.isBefore(expired) ?: true
            val overQuota = maxBytes != null && total > maxBytes
            if (!tooOld && !overQuota) break // oldest first: the next ones are younger
            candidate.storage_path?.let(store::delete)
            database.query { mediaQueries.deleteMediaFile(candidate.episode_id) }
            total -= candidate.size_bytes ?: 0
            purged++
        }
        if (purged > 0) log.info("Purged {} files", purged)
        return purged
    }

    /** Before a podcast is deleted: its files go too. */
    suspend fun deletePodcastFiles(podcastId: Long) {
        database.query { mediaQueries.filesOfPodcast(podcastId).executeAsList() }.forEach(store::delete)
    }

    private data class StoredFile(val path: String, val size: Long, val mimeType: String)

    private suspend fun download(podcastId: Long, episodeId: Long, url: String, declaredType: String?): StoredFile =
        http.get(
            url,
            configure = {
                header(HttpHeaders.UserAgent, SafeHttp.USER_AGENT)
                timeout {
                    requestTimeoutMillis = 30.minutes.inWholeMilliseconds
                    socketTimeoutMillis = 60_000
                }
            },
        ) { response, finalUrl, _ ->
            if (!response.status.isSuccess()) throw FeedException.Unreachable("HTTP ${response.status.value} for $finalUrl")
            val mimeType = response.contentType()?.withoutParameters()?.toString()?.takeIf { it.startsWith("audio/") || it.startsWith("video/") }
                ?: declaredType ?: "audio/mpeg"
            val relative = "$podcastId/$episodeId.${extensionFor(mimeType, finalUrl)}"
            val target = store.resolve(relative)
            val partial = target.resolveSibling("${target.fileName}.part")
            withContext(Dispatchers.IO) {
                try {
                    Files.createDirectories(target.parent)
                    LimitedInputStream(response.bodyAsChannel().toInputStream(), maxFileBytes).use { input ->
                        Files.newOutputStream(partial).use { input.copyTo(it) }
                    }
                    Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } finally {
                    Files.deleteIfExists(partial)
                }
            }
            StoredFile(relative, Files.size(target), mimeType)
        }

    private fun extensionFor(mimeType: String, url: Url): String = when (mimeType.lowercase()) {
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/mp4", "audio/x-m4a", "audio/m4a", "audio/aac" -> "m4a"
        "audio/ogg" -> "ogg"
        "audio/opus" -> "opus"
        "audio/wav", "audio/x-wav" -> "wav"
        "video/mp4" -> "mp4"
        else -> url.encodedPath.substringAfterLast('.', "").lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,5}")) } ?: "mp3"
    }
}
