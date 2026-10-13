package fr.podcast.server.catalog

import fr.podcast.server.db.PodcastDb
import fr.podcast.server.db.ServerDatabase
import fr.podcast.server.db.toKotlinInstant
import fr.podcast.shared.home.EpisodeItem
import fr.podcast.shared.model.Episode
import fr.podcast.shared.model.EpisodeStatus
import fr.podcast.shared.model.FileStatus
import fr.podcast.shared.model.Podcast
import fr.podcast.shared.model.RefreshStatus
import fr.podcast.shared.model.Subscription
import fr.podcast.shared.model.SubscriptionItem
import fr.podcast.shared.model.Transcript
import fr.podcast.shared.repository.PodcastRepository
import java.time.OffsetDateTime

data class Page<T>(val items: List<T>, val total: Long, val offset: Int, val limit: Int)

/**
 * The catalog as seen by one user: shared podcasts and episodes plus that user's own state.
 * Also the server-side [PodcastRepository], so LoadHomeState works the same as on the clients.
 */
class UserCatalog(private val database: ServerDatabase, private val userId: Long) : PodcastRepository {

    // --- PodcastRepository ---

    override suspend fun subscriptions(): List<Podcast> = subscriptionList().map { it.podcast }

    override suspend fun podcast(id: String): Podcast? {
        val podcastId = id.toLongOrNull() ?: return null
        return database.query { catalogQueries.podcastById(podcastId, ::podcast).executeAsOneOrNull() }
    }

    override suspend fun episode(id: String): Episode? = episodeItem(id)?.episode

    override suspend fun latestEpisodes(limit: Int): List<Episode> = latest(status = null, limit = limit, offset = 0).items.map { it.episode }

    override suspend fun inProgress(): List<Episode> = resume(limit = 50).map { it.episode }

    override suspend fun updateProgress(episodeId: String, positionSeconds: Int) {
        val id = episodeId.toLongOrNull() ?: return
        database.query { episodesQueries.saveProgress(userId, id, positionSeconds, EpisodeStatus.IN_PROGRESS.name) }
    }

    override suspend fun markPlayed(episodeId: String) {
        val id = episodeId.toLongOrNull() ?: return
        database.transaction {
            val duration = catalogQueries.episodeDuration(id).executeAsOneOrNull() ?: return@transaction
            episodesQueries.saveProgress(userId, id, duration, EpisodeStatus.PLAYED.name)
        }
    }

    override suspend fun markUnplayed(episodeId: String) {
        val id = episodeId.toLongOrNull() ?: return
        database.query { episodesQueries.markUnplayed(userId, id) }
    }

    // --- Richer queries used by the API ---

    /** Marks the episode as kept: its server file is never purged (ADR 0007). */
    suspend fun setKept(episodeId: String, kept: Boolean) {
        val id = episodeId.toLongOrNull() ?: return
        database.query { episodesQueries.setKept(userId, id, kept) }
    }

    suspend fun episodeItem(id: String): EpisodeItem? {
        val episodeId = id.toLongOrNull() ?: return null
        return database.query {
            episodesQueries.episodeForUser(userId, episodeId, ::episodeItem).executeAsOneOrNull()?.let { withTranscripts(listOf(it)).single() }
        }
    }

    suspend fun latest(status: EpisodeStatus?, limit: Int, offset: Int): Page<EpisodeItem> = database.query {
        val filter = status != null
        val name = status?.name.orEmpty()
        val items = episodesQueries.latestForUser(userId, filter, name, limit.toLong(), offset.toLong(), ::episodeItem).executeAsList()
        val total = episodesQueries.countLatestForUser(userId, filter, name).executeAsOne()
        Page(withTranscripts(items), total, offset, limit)
    }

    suspend fun resume(limit: Int): List<EpisodeItem> =
        database.query { withTranscripts(episodesQueries.resumeForUser(userId, limit.toLong(), ::episodeItem).executeAsList()) }

    /** Null when the podcast does not exist. */
    suspend fun podcastEpisodes(podcastId: String, limit: Int, offset: Int): Page<EpisodeItem>? {
        val id = podcastId.toLongOrNull() ?: return null
        return database.query {
            catalogQueries.podcastById(id).executeAsOneOrNull() ?: return@query null
            val items = episodesQueries.podcastEpisodesForUser(userId, id, limit.toLong(), offset.toLong(), ::episodeItem).executeAsList()
            Page(withTranscripts(items), catalogQueries.countPodcastEpisodes(id).executeAsOne(), offset, limit)
        }
    }

    suspend fun subscriptionList(): List<SubscriptionItem> =
        database.query { catalogQueries.subscriptionsOfUser(userId, ::subscriptionItem).executeAsList() }

    suspend fun subscription(id: String): SubscriptionItem? {
        val subscriptionId = id.toLongOrNull() ?: return null
        return database.query { catalogQueries.subscriptionOfUser(userId, subscriptionId, ::subscriptionItem).executeAsOneOrNull() }
    }

    /** Subscribes to an existing podcast; the boolean tells whether it is a new subscription. */
    suspend fun subscribe(podcastId: Long): Pair<SubscriptionItem, Boolean> = database.transaction {
        val existing = catalogQueries.subscriptionIdFor(userId, podcastId).executeAsOneOrNull()
        val id = existing ?: catalogQueries.insertSubscription(userId, podcastId, null).executeAsOne()
        catalogQueries.subscriptionOfUser(userId, id, ::subscriptionItem).executeAsOne() to (existing == null)
    }

    suspend fun updateSubscription(id: String, note: String?, autoDownload: Boolean): SubscriptionItem? {
        val subscriptionId = id.toLongOrNull() ?: return null
        return database.transaction {
            catalogQueries.updateSubscription(note, autoDownload, subscriptionId, userId)
            catalogQueries.subscriptionOfUser(userId, subscriptionId, ::subscriptionItem).executeAsOneOrNull()
        }
    }

    /**
     * Removes the subscription. Returns the podcast id when nobody follows that podcast any more
     * (the caller removes it with its files), 0 when others still do, null if not found.
     */
    suspend fun unsubscribe(id: String): Long? {
        val subscriptionId = id.toLongOrNull() ?: return null
        return database.transaction {
            val podcastId = catalogQueries.deleteSubscription(subscriptionId, userId).executeAsOneOrNull() ?: return@transaction null
            if (catalogQueries.countSubscribers(podcastId).executeAsOne() == 0L) podcastId else 0L
        }
    }

    suspend fun subscribedFeedUrls(): Set<String> =
        database.query { catalogQueries.subscribedFeedUrls(userId).executeAsList().toSet() }

    suspend fun audioUrl(episodeId: String): String? {
        val id = episodeId.toLongOrNull() ?: return null
        return database.query { catalogQueries.episodeAudio(id).executeAsOneOrNull() }
    }
}

/** Adds the Podcast 2.0 transcripts, in one query for the whole list. */
private fun PodcastDb.withTranscripts(items: List<EpisodeItem>): List<EpisodeItem> {
    if (items.isEmpty()) return items
    val transcripts = catalogQueries.transcriptsOf(items.map { it.episode.id.toLong() }) { episodeId, url, type, language ->
        episodeId.toString() to Transcript(url, type, language)
    }.executeAsList().groupBy({ it.first }, { it.second })
    return items.map { item ->
        transcripts[item.episode.id]?.let { item.copy(episode = item.episode.copy(transcripts = it)) } ?: item
    }
}

@Suppress("FunctionParameterNaming")
internal fun podcast(
    id: Long, title: String, author: String, feed_url: String, artwork_url: String?, description: String,
    website_url: String?, language: String?, refresh_status: String, last_refreshed_at: OffsetDateTime?,
) = Podcast(
    id = id.toString(),
    title = title,
    author = author,
    feedUrl = feed_url,
    artworkUrl = artwork_url,
    description = description,
    websiteUrl = website_url,
    language = language,
    refreshStatus = RefreshStatus.valueOf(refresh_status),
    refreshedAt = last_refreshed_at?.toKotlinInstant(),
)

// Every episode query returns the same columns (see Episodes.sq).
@Suppress("FunctionParameterNaming", "UnusedParameter")
private fun episodeItem(
    id: Long, podcast_id: Long, title: String, description: String, published_at: OffsetDateTime, duration_seconds: Int,
    chapters_url: String?, chapters_type: String?, file_status: String,
    podcast_title: String, podcast_author: String, podcast_description: String, podcast_feed_url: String, podcast_artwork_url: String?,
    podcast_website_url: String?, podcast_language: String?, podcast_refresh_status: String, podcast_refreshed_at: OffsetDateTime?,
    position_seconds: Int, status: String, kept: Boolean,
) = EpisodeItem(
    episode = Episode(
        id = id.toString(),
        podcastId = podcast_id.toString(),
        title = title,
        publishedAt = published_at.toKotlinInstant(),
        durationSeconds = duration_seconds,
        positionSeconds = position_seconds,
        status = EpisodeStatus.valueOf(status),
        description = description,
        kept = kept,
        fileStatus = FileStatus.valueOf(file_status),
        chaptersUrl = chapters_url,
    ),
    podcast = podcast(
        podcast_id, podcast_title, podcast_author, podcast_feed_url, podcast_artwork_url, podcast_description,
        podcast_website_url, podcast_language, podcast_refresh_status, podcast_refreshed_at,
    ),
)

@Suppress("FunctionParameterNaming")
private fun subscriptionItem(
    id: Long, note: String?, subscribed_at: OffsetDateTime, auto_download: Boolean,
    podcast_id: Long, title: String, author: String, feed_url: String, artwork_url: String?, description: String,
    website_url: String?, language: String?, refresh_status: String, last_refreshed_at: OffsetDateTime?,
) = SubscriptionItem(
    subscription = Subscription(id.toString(), note, subscribed_at.toKotlinInstant(), auto_download),
    podcast = podcast(podcast_id, title, author, feed_url, artwork_url, description, website_url, language, refresh_status, last_refreshed_at),
)
