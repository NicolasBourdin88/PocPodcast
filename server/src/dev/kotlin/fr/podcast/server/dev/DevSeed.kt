package fr.podcast.server.dev

import fr.podcast.server.auth.Accounts
import fr.podcast.server.auth.Role
import fr.podcast.server.catalog.UserCatalog
import fr.podcast.server.db.ServerDatabase
import fr.podcast.server.feeds.FeedException
import fr.podcast.server.feeds.FeedRefresher
import fr.podcast.shared.model.EpisodeStatus
import org.slf4j.LoggerFactory

/**
 * Demo data for local development, read from real feeds on the first start.
 * Never part of the production distribution; tests use their own deterministic data.
 *
 * Demo accounts (development only):
 * - alice / [ALICE_PASSWORD] (admin), subscribed to the four podcasts below
 * - bob / [BOB_PASSWORD], subscribed to Talking Kotlin and Podnews Weekly Review
 */
object DevSeed {
    const val ALICE = "alice"
    const val ALICE_PASSWORD = "podcast-alice"
    const val BOB = "bob"
    const val BOB_PASSWORD = "podcast-bob"

    private class SeedFeed(val title: String, val url: String, val bob: Boolean)

    private val feeds = listOf(
        SeedFeed("Les Cast Codeurs Podcast", "https://lescastcodeurs.libsyn.com/rss", bob = false),
        SeedFeed("Talking Kotlin", "https://anchor.fm/s/fdcf2fa8/podcast/rss", bob = true),
        SeedFeed("Fragmented", "https://feeds.simplecast.com/LpAGSLnY", bob = false),
        // Uses Podcast 2.0 chapters and transcripts.
        SeedFeed("Podnews Weekly Review", "https://feeds.buzzsprout.com/1538779.rss", bob = true),
    )

    private val log = LoggerFactory.getLogger(DevSeed::class.java)

    suspend fun seedIfEmpty(database: ServerDatabase, refresher: FeedRefresher) {
        if (database.query { usersQueries.countUsers().executeAsOne() } == 0L) seed(database, refresher)
    }

    suspend fun seed(database: ServerDatabase, refresher: FeedRefresher) {
        val accounts = Accounts(database)
        val alice = UserCatalog(database, accounts.create(ALICE, "Alice", ALICE_PASSWORD, Role.ADMIN).id)
        val bob = UserCatalog(database, accounts.create(BOB, "Bob", BOB_PASSWORD).id)

        val podcastIds = feeds.map { feed ->
            val podcastId = try {
                refresher.podcastFor(feed.url)
            } catch (offline: FeedException) {
                // No network: the podcast waits for the background refresh.
                log.warn("{} not read ({}); it will be read later", feed.title, offline.message)
                database.query { catalogQueries.insertPendingPodcast(feed.url, feed.title).executeAsOneOrNull() }
                    ?: database.query { catalogQueries.podcastIdByFeedUrl(feed.url).executeAsOne() }
            }
            alice.subscribe(podcastId)
            if (feed.bob) bob.subscribe(podcastId)
            podcastId
        }

        // Some listening already started, so that "Continue listening" is not empty.
        // (By id: reading a feed may update its address, e.g. itunes:new-feed-url.)
        alice.startListening(podcastIds[0], fraction = 0.4)
        alice.startListening(podcastIds[3], fraction = 0.7)
        bob.startListening(podcastIds[1], fraction = 0.25)
        log.info("Demo data ready (see DevSeed.kt for the accounts)")
    }

    /** Puts the newest episode of the podcast in progress at [fraction] of its duration. */
    private suspend fun UserCatalog.startListening(podcastId: Long, fraction: Double) {
        val newest = podcastEpisodes(podcastId.toString(), limit = 1, offset = 0)?.items?.firstOrNull()?.episode ?: return
        val duration = newest.durationSeconds.takeIf { it > 0 } ?: 3600
        updateProgress(newest.id, (duration * fraction).toInt())
        check(episode(newest.id)?.status == EpisodeStatus.IN_PROGRESS)
    }
}
