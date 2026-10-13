package fr.podcast.server.catalog

import fr.podcast.server.db.ServerDatabase
import fr.podcast.server.db.toKotlinInstant
import fr.podcast.shared.home.EpisodeItem
import fr.podcast.shared.model.Episode
import fr.podcast.shared.model.EpisodeStatus
import fr.podcast.shared.model.Podcast
import fr.podcast.shared.repository.PodcastRepository
import java.time.OffsetDateTime
import kotlin.time.Instant

data class Page<T>(val items: List<T>, val total: Long, val offset: Int, val limit: Int)

/** A user's subscription: their own entry pointing to a shared podcast. */
data class Subscription(val id: String, val note: String?, val subscribedAt: Instant, val podcast: Podcast)

/**
 * The catalog as seen by one user: shared podcasts and episodes plus that user's own state.
 * Also the server-side [PodcastRepository], so LoadHomeState works the same as on the clients.
 */
class UserCatalog(private val database: ServerDatabase, private val userId: Long) : PodcastRepository {

    // --- PodcastRepository ---

    override suspend fun subscriptions(): List<Podcast> = subscriptionList().map { it.podcast }

    override suspend fun podcast(id: String): Podcast? {
        val podcastId = id.toLongOrNull() ?: return null
        return database.query {
            catalogQueries.podcastById(podcastId) { id, title, author, feedUrl, artworkUrl, description ->
                Podcast(id.toString(), title, author, feedUrl, artworkUrl, description)
            }.executeAsOneOrNull()
        }
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
        database.query { episodesQueries.resetProgress(userId, id) }
    }

    // --- Richer queries used by the API ---

    suspend fun episodeItem(id: String): EpisodeItem? {
        val episodeId = id.toLongOrNull() ?: return null
        return database.query { episodesQueries.episodeForUser(userId, episodeId, ::episodeItem).executeAsOneOrNull() }
    }

    suspend fun latest(status: EpisodeStatus?, limit: Int, offset: Int): Page<EpisodeItem> = database.query {
        val filter = status != null
        val name = status?.name.orEmpty()
        val items = episodesQueries.latestForUser(userId, filter, name, limit.toLong(), offset.toLong(), ::episodeItem).executeAsList()
        val total = episodesQueries.countLatestForUser(userId, filter, name).executeAsOne()
        Page(items, total, offset, limit)
    }

    suspend fun resume(limit: Int): List<EpisodeItem> =
        database.query { episodesQueries.resumeForUser(userId, limit.toLong(), ::episodeItem).executeAsList() }

    /** Null when the podcast does not exist. */
    suspend fun podcastEpisodes(podcastId: String, limit: Int, offset: Int): Page<EpisodeItem>? {
        val id = podcastId.toLongOrNull() ?: return null
        return database.query {
            catalogQueries.podcastById(id).executeAsOneOrNull() ?: return@query null
            val items = episodesQueries.podcastEpisodesForUser(userId, id, limit.toLong(), offset.toLong(), ::episodeItem).executeAsList()
            Page(items, catalogQueries.countPodcastEpisodes(id).executeAsOne(), offset, limit)
        }
    }

    suspend fun subscriptionList(): List<Subscription> =
        database.query { catalogQueries.subscriptionsOfUser(userId, ::subscription).executeAsList() }

    suspend fun subscription(id: String): Subscription? {
        val subscriptionId = id.toLongOrNull() ?: return null
        return database.query { catalogQueries.subscriptionOfUser(userId, subscriptionId, ::subscription).executeAsOneOrNull() }
    }

    suspend fun audioUrl(episodeId: String): String? {
        val id = episodeId.toLongOrNull() ?: return null
        return database.query { catalogQueries.episodeAudio(id).executeAsOneOrNull() }
    }
}

// Every episode query returns the same columns (see Episodes.sq).
@Suppress("LocalVariableName", "FunctionParameterNaming")
private fun episodeItem(
    id: Long, podcast_id: Long, title: String, description: String, published_at: OffsetDateTime, duration_seconds: Int,
    podcast_title: String, podcast_author: String, podcast_description: String, podcast_feed_url: String, podcast_artwork_url: String?,
    position_seconds: Int, status: String,
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
    ),
    podcast = Podcast(podcast_id.toString(), podcast_title, podcast_author, podcast_feed_url, podcast_artwork_url, podcast_description),
)

@Suppress("FunctionParameterNaming")
private fun subscription(
    id: Long, note: String?, subscribed_at: OffsetDateTime,
    podcast_id: Long, title: String, author: String, feed_url: String, artwork_url: String?, description: String,
) = Subscription(
    id = id.toString(),
    note = note,
    subscribedAt = subscribed_at.toKotlinInstant(),
    podcast = Podcast(podcast_id.toString(), title, author, feed_url, artwork_url, description),
)
