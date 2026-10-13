package fr.podcast.server.feeds

import fr.podcast.server.db.PodcastDb
import fr.podcast.server.db.ServerDatabase
import fr.podcast.server.db.toOffsetDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.time.Duration

/**
 * Keeps the catalog in sync with the feeds: creates a podcast from its feed, then reads it
 * again periodically (conditional requests, so unchanged feeds cost a 304).
 */
class FeedRefresher(
    private val database: ServerDatabase,
    private val fetcher: FeedFetcher,
    /** Called after new or updated episodes, e.g. to schedule automatic downloads. */
    private val onEpisodesStored: suspend (podcastId: Long) -> Unit = {},
) {
    private val log = LoggerFactory.getLogger(FeedRefresher::class.java)

    /**
     * The podcast for [feedUrl], created from the feed if it is new: a podcast only exists
     * if its feed could be read.
     *
     * @throws FeedException when the feed cannot be read.
     */
    suspend fun podcastFor(feedUrl: String): Long {
        findPodcast(feedUrl)?.let { return it }
        val fetched = fetcher.fetch(feedUrl) as? FetchResult.Fetched
            ?: throw FeedException.Unreachable("Unexpected 304 for $feedUrl")
        val url = fetched.movedTo ?: feedUrl
        findPodcast(url)?.let { return it }
        val podcastId = try {
            database.transaction {
                val feed = fetched.feed
                val id = catalogQueries.insertPodcast(url, feed.title, feed.author, feed.description, feed.artworkUrl, feed.websiteUrl).executeAsOne()
                store(id, fetched)
                id
            }
        } catch (alreadyThere: Exception) {
            // Two users subscribing to the same new feed at once: the other request created it.
            findPodcast(url) ?: throw alreadyThere
        }
        onEpisodesStored(podcastId)
        return podcastId
    }

    /** Reads the podcast's feed again; failures are recorded on the podcast, never thrown. */
    suspend fun refresh(podcastId: Long): Boolean {
        val podcast = database.query { catalogQueries.podcastFeed(podcastId).executeAsOneOrNull() } ?: return false
        return try {
            when (val result = fetcher.fetch(podcast.feed_url, podcast.http_etag, podcast.http_last_modified)) {
                FetchResult.NotModified -> database.query { catalogQueries.markPodcastUnchanged(podcastId) }
                is FetchResult.Fetched -> {
                    database.transaction { store(podcastId, result) }
                    onEpisodesStored(podcastId)
                }
            }
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            log.info("Feed of podcast {} not read: {}", podcastId, failure.message)
            database.query { catalogQueries.markPodcastFailed(failure.message?.take(500), podcastId) }
            false
        }
    }

    /** Reads every followed feed not read for [interval], a few at a time. */
    suspend fun refreshDue(interval: Duration, limit: Int = 100, parallelism: Int = 4) {
        val before = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(interval.inWholeSeconds)
        val due = database.query { catalogQueries.podcastsDueForRefresh(before, limit.toLong()).executeAsList() }
        if (due.isEmpty()) return
        val permits = Semaphore(parallelism)
        coroutineScope {
            due.map { id -> async { permits.withPermit { refresh(id) } } }.awaitAll()
        }
        log.info("Read {} feeds", due.size)
    }

    private suspend fun findPodcast(feedUrl: String): Long? =
        database.query { catalogQueries.podcastIdByFeedUrl(feedUrl).executeAsOneOrNull() }

    /** Podcast fields, then episodes by guid with their transcripts. Runs inside a transaction. */
    private fun PodcastDb.store(podcastId: Long, fetched: FetchResult.Fetched) {
        val feed = fetched.feed
        catalogQueries.updatePodcastFromFeed(
            title = feed.title, author = feed.author, description = feed.description, artworkUrl = feed.artworkUrl,
            websiteUrl = feed.websiteUrl, language = feed.language, etag = fetched.etag, lastModified = fetched.lastModified,
            id = podcastId,
        )
        (fetched.movedTo ?: feed.newFeedUrl)?.let { catalogQueries.moveFeed(it, podcastId) }
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        for (item in feed.items) {
            val episodeId = catalogQueries.upsertEpisode(
                podcastId = podcastId, guid = item.guid, title = item.title, description = item.description,
                publishedAt = item.publishedAt?.toOffsetDateTime() ?: now, durationSeconds = item.durationSeconds,
                audioUrl = item.audioUrl, audioType = item.audioType, audioLength = item.audioLength,
                chaptersUrl = item.chaptersUrl, chaptersType = item.chaptersType, dateKnown = item.publishedAt != null,
            ).executeAsOne()
            catalogQueries.deleteTranscripts(episodeId)
            item.transcripts.forEachIndexed { position, transcript ->
                catalogQueries.insertTranscript(episodeId, transcript.url, transcript.type, transcript.language, position)
            }
        }
    }
}
