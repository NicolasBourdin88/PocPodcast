package fr.podcast.server.sync

import fr.podcast.server.auth.UserAccount
import fr.podcast.server.opml.OpmlEntry
import fr.podcast.server.catalog.Playlists
import fr.podcast.server.catalog.UserCatalog
import fr.podcast.server.db.ServerDatabase
import fr.podcast.server.feeds.UrlGuard
import fr.podcast.server.search.normalizedFeed
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/** A gPodder device: the id its client chose, a name and a type (`mobile`, `desktop`…). */
@Serializable
data class GpodderDevice(val id: String, val caption: String, val type: String, val subscriptions: Int)

/** Subscription changes in the gPodder format; [timestamp] is the next `since`. */
@Serializable
data class SubscriptionDelta(val add: List<String>, val remove: List<String>, val timestamp: Long)

/** The answer to an upload: the next `since`, and the URLs rewritten as `[before, after]` pairs. */
@Serializable
data class UploadAnswer(val timestamp: Long, val update_urls: List<List<String>> = emptyList())

/** An episode action in the gPodder format (`play`, `new`, `download`, `delete`). */
@Serializable
data class EpisodeAction(
    val podcast: String,
    val episode: String,
    val guid: String? = null,
    val device: String? = null,
    val action: String,
    val timestamp: String? = null,
    val started: Int? = null,
    val position: Int? = null,
    val total: Int? = null,
)

@Serializable
data class EpisodeActions(val actions: List<EpisodeAction>, val timestamp: Long)

/**
 * The gPodder API as AntennaPod uses it (ADR 0011): the user's subscriptions for every device,
 * and episode actions turned into the user's listening state, and back.
 */
class GpodderSync(
    private val database: ServerDatabase,
    private val guard: UrlGuard,
    /** To recognise the enclosures of our playlist feeds (ADR 0010). */
    private val playlists: Playlists,
    /** Reads new feeds in the background, as after an OPML import. */
    private val refreshSoon: (List<Long>) -> Unit,
    /** Removes a podcast nobody follows any more, with its files. */
    private val removePodcast: suspend (Long) -> Unit,
    private val pendingLifetime: Duration = 30.days,
) {
    private val log = LoggerFactory.getLogger(GpodderSync::class.java)

    suspend fun devices(user: UserAccount): List<GpodderDevice> = database.query {
        val subscriptions = syncQueries.subscribedPodcasts(user.id).executeAsList().size
        syncQueries.gpodderDevices(user.id).executeAsList().map { GpodderDevice(it.device_id, it.caption, it.type, subscriptions) }
    }

    /** Creates the device, or changes what is given of it. */
    suspend fun saveDevice(user: UserAccount, deviceId: String, caption: String?, type: String?) = database.transaction {
        val current = syncQueries.gpodderDevice(user.id, deviceId).executeAsOneOrNull()
        syncQueries.saveGpodderDevice(user.id, deviceId, caption ?: current?.caption ?: "", type ?: current?.type ?: "other")
    }

    /** Since 0, every current subscription; otherwise what changed since [since] (seconds, inclusive). */
    suspend fun subscriptionChanges(user: UserAccount, since: Long): SubscriptionDelta {
        val timestamp = now()
        return database.query {
            if (since <= 0) {
                SubscriptionDelta(syncQueries.subscribedPodcasts(user.id).executeAsList().map { it.feed_url }.distinct(), emptyList(), timestamp)
            } else {
                val changes = syncQueries.subscriptionChangesSince(user.id, since.toTime()).executeAsList()
                SubscriptionDelta(changes.filter { it.subscribed }.map { it.feed_url }, changes.filterNot { it.subscribed }.map { it.feed_url }, timestamp)
            }
        }
    }

    /**
     * Adds as an OPML import does (pending podcasts, read in the background, SSRF guard), removes
     * by feed. A URL that is not http(s) is rewritten to "" and ignored, as the API says.
     */
    suspend fun uploadSubscriptionChanges(user: UserAccount, add: List<String>, remove: List<String>): UploadAnswer {
        val rewritten = mutableListOf<List<String>>()
        fun clean(url: String): String? {
            val trimmed = url.trim()
            val kept = trimmed.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
            if (kept != url) rewritten += listOf(url, kept.orEmpty())
            return kept
        }
        val added = add.mapNotNull(::clean)
        val removed = remove.mapNotNull(::clean)

        val catalog = UserCatalog(database, user.id)
        if (added.isNotEmpty()) {
            val import = catalog.importFeeds(added.map { OpmlEntry(it, it) }, guard)
            import.report.failed.forEach { log.info("gPodder subscription to {} refused: {}", it.feedUrl, it.reason) }
            refreshSoon(import.newPodcasts)
        }
        for (url in removed) {
            val key = normalizedFeed(url)
            val subscription = database.query { syncQueries.subscribedPodcasts(user.id).executeAsList() }
                .firstOrNull { normalizedFeed(it.feed_url) == key }
            if (subscription == null) {
                // Not followed here: still tell the other devices.
                database.query { syncQueries.recordSubscriptionChange(user.id, url, false) }
                continue
            }
            val orphan = catalog.unsubscribe(subscription.subscription_id.toString())
            if (orphan != null && orphan > 0) removePodcast(orphan)
        }
        return UploadAnswer(now(), rewritten)
    }

    /**
     * The state of every episode changed since [since] as a `play` action (played: position =
     * total), and the actions received for episodes not known yet.
     */
    suspend fun episodeActions(user: UserAccount, since: Long, podcast: String?): EpisodeActions {
        val timestamp = now()
        val from = since.toTime()
        return database.query {
            val states = syncQueries.statesChangedSince(user.id, from, podcast.orEmpty()).executeAsList().map { state ->
                val total = state.duration_seconds.takeIf { it > 0 } ?: maxOf(state.position_seconds, 1)
                val played = state.status == "PLAYED"
                EpisodeAction(
                    podcast = state.feed_url,
                    episode = state.audio_url,
                    guid = state.guid,
                    action = "play",
                    timestamp = state.updated_at.format(),
                    started = if (played) total else 0,
                    position = if (played) total else minOf(state.position_seconds, total),
                    total = total,
                )
            }
            val pending = syncQueries.pendingActionsSince(user.id, from, podcast.orEmpty()).executeAsList().map {
                EpisodeAction(it.podcast_url, it.episode_url, it.guid, it.device_id, it.action, it.happened_at.format(), it.started, it.position, it.total)
            }
            EpisodeActions(states + pending, timestamp)
        }
    }

    /**
     * Applies each action to the user's state when its date is the newest; keeps those whose
     * episode is not known yet. `download` and `delete` concern the device's files: ignored.
     */
    suspend fun uploadEpisodeActions(user: UserAccount, actions: List<EpisodeAction>): UploadAnswer {
        var subscribed: List<fr.podcast.server.db.SubscribedPodcasts>? = null
        suspend fun podcasts() = subscribed ?: database.query { syncQueries.subscribedPodcasts(user.id).executeAsList() }.also { subscribed = it }
        for (action in actions) {
            val kind = action.action.lowercase()
            if (kind != "play" && kind != "new") continue
            val happened = action.timestamp?.let(::parseTime) ?: OffsetDateTime.now(ZoneOffset.UTC)
            val key = normalizedFeed(action.podcast)
            val podcastId = database.query { catalogQueries.podcastIdByFeedUrl(action.podcast).executeAsOneOrNull() }
                ?: podcasts().firstOrNull { normalizedFeed(it.feed_url) == key }?.podcast_id
            // Listened to through one of our playlist feeds: its enclosure names the episode.
            val episode = playlistEnclosure(action.episode) ?: podcastId?.let { findEpisode(it, action.guid, action.episode) }
            if (episode != null) {
                apply(user.id, episode, kind, action.position, action.total, happened)
            } else {
                database.query {
                    syncQueries.insertPendingAction(
                        user.id, action.device, action.podcast, key, action.episode, action.guid?.takeIf { it.isNotBlank() },
                        kind, action.started, action.position, action.total, happened,
                    )
                }
            }
        }
        database.query { syncQueries.prunePendingActions(OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(pendingLifetime.inWholeSeconds)) }
        return UploadAnswer(now())
    }

    /** After a refresh of the podcast: the actions waiting for its episodes. */
    suspend fun applyPending(podcastId: Long) {
        val feedUrl = database.query { catalogQueries.podcastFeed(podcastId).executeAsOneOrNull()?.feed_url } ?: return
        val waiting = database.query { syncQueries.pendingActionsForFeed(normalizedFeed(feedUrl)).executeAsList() }
        for (action in waiting) {
            val episode = findEpisode(podcastId, action.guid, action.episode_url) ?: continue
            apply(action.user_id, episode, action.action, action.position, action.total, action.happened_at)
            database.query { syncQueries.deletePendingAction(action.id) }
        }
    }

    private class Episode(val id: Long, val durationSeconds: Int)

    /** `…/feeds/{secret}/episodes/{id}`, the enclosure of a published playlist: that episode. */
    private suspend fun playlistEnclosure(audioUrl: String): Episode? {
        val (secret, id) = PLAYLIST_ENCLOSURE.find(audioUrl)?.destructured ?: return null
        playlists.byFeedSecret(secret) ?: return null
        val episodeId = id.toLongOrNull() ?: return null
        return database.query { catalogQueries.episodeDuration(episodeId).executeAsOneOrNull() }?.let { Episode(episodeId, it) }
    }

    /** By the feed's guid first, then by the address of the audio file. */
    private suspend fun findEpisode(podcastId: Long, guid: String?, audioUrl: String): Episode? = database.query {
        guid?.takeIf { it.isNotBlank() }?.let { syncQueries.episodeByGuid(podcastId, it).executeAsOneOrNull() }?.let { Episode(it.id, it.duration_seconds) }
            ?: syncQueries.episodeByAudioUrl(podcastId, audioUrl).executeAsOneOrNull()?.let { Episode(it.id, it.duration_seconds) }
    }

    /**
     * The newest date wins: an action older than the last change of the state is ignored. gPodder
     * dates stop at the second, so an action of the same second as that change wins.
     */
    private suspend fun apply(userId: Long, episode: Episode, kind: String, position: Int?, total: Int?, happened: OffsetDateTime) {
        val changedAt = database.query { syncQueries.stateChangedAt(userId, episode.id).executeAsOneOrNull() }
        if (changedAt != null && happened.isBefore(changedAt.truncatedTo(ChronoUnit.SECONDS))) return
        val catalog = UserCatalog(database, userId)
        val id = episode.id.toString()
        when (kind) {
            "new" -> catalog.markUnplayed(id)
            "play" -> {
                val seconds = position?.takeIf { it > 0 } ?: return
                val length = total?.takeIf { it > 0 } ?: episode.durationSeconds.takeIf { it > 0 }
                if (length != null && seconds >= length - PLAYED_MARGIN_SECONDS) {
                    catalog.markPlayed(id)
                } else {
                    catalog.updateProgress(id, seconds.coerceAtMost(MAX_POSITION_SECONDS))
                }
            }
        }
    }

    private companion object {
        /** gPodder clients send "played" as position = total; a few seconds short counts too. */
        const val PLAYED_MARGIN_SECONDS = 5

        /** As for our own clients (ADR 0008). */
        const val MAX_POSITION_SECONDS = 24 * 3600

        val PLAYLIST_ENCLOSURE = Regex("""/feeds/([A-Za-z0-9_-]+)/episodes/(\d+)$""")

        /** gPodder dates: UTC, to the second, without zone ("2026-11-18T08:19:12"). */
        val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    }

    private fun now(): Long = Instant.now().epochSecond

    private fun Long.toTime(): OffsetDateTime = OffsetDateTime.ofInstant(Instant.ofEpochSecond(coerceAtLeast(0)), ZoneOffset.UTC)

    private fun OffsetDateTime.format(): String = withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS).format(FORMAT)

    /** As gPodder clients send it, in UTC; with a zone or fractions of seconds too. */
    private fun parseTime(value: String): OffsetDateTime? =
        runCatching { LocalDateTime.parse(value).atOffset(ZoneOffset.UTC) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value) }.getOrNull()
}
