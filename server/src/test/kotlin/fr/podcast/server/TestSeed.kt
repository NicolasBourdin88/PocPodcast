package fr.podcast.server

import fr.podcast.server.auth.Accounts
import fr.podcast.server.auth.Role
import fr.podcast.server.db.ServerDatabase
import fr.podcast.server.db.toOffsetDateTime
import fr.podcast.shared.model.EpisodeStatus
import kotlin.time.Instant

/**
 * Deterministic test data, without network: feeds point to https://feeds.test (see TestFeeds).
 *
 * - users 1 = alice (admin, subscribed to the 4 podcasts), 2 = bob (Talking Kotlin and Fragmented)
 * - podcasts 1 to 4, episodes 1 to 9 (most recent first), subscriptions 1 to 6
 */
object TestSeed {
    const val ALICE = "alice"
    const val ALICE_PASSWORD = "test-alice"
    const val BOB = "bob"
    const val BOB_PASSWORD = "test-bob"

    private class SeedPodcast(val key: String, val title: String, val author: String, val description: String)
    private class SeedEpisode(val podcast: String, val title: String, val publishedAt: String, val minutes: Int)

    private val podcasts = listOf(
        SeedPodcast("lcc", "Les Cast Codeurs", "Emmanuel Bernard & co", "Le podcast Java en français."),
        SeedPodcast("tk", "Talking Kotlin", "JetBrains", "A podcast about Kotlin and more."),
        SeedPodcast("cac", "Le Code a changé", "France Inter", "Comment le numérique change nos vies."),
        SeedPodcast("frag", "Fragmented", "Donn Felker & Kaushik Gopal", "An Android developer podcast."),
    )

    // Most recent first; the first five match FakePodcastRepository.
    private val episodes = listOf(
        SeedEpisode("tk", "Compose Multiplatform for iOS is stable", "2026-10-12T06:00:00Z", 52),
        SeedEpisode("lcc", "LCC 330 - Les nouveautés de Kotlin 2.4", "2026-10-10T05:00:00Z", 95),
        SeedEpisode("frag", "KMP in production", "2026-10-08T12:00:00Z", 47),
        SeedEpisode("cac", "Qui a inventé le copier-coller ?", "2026-10-05T04:30:00Z", 34),
        SeedEpisode("tk", "Ktor 3 and server-side Kotlin", "2026-09-28T06:00:00Z", 61),
        SeedEpisode("lcc", "LCC 329 - Rentrée 2026", "2026-09-19T05:00:00Z", 88),
        SeedEpisode("frag", "Gradle, the good parts", "2026-09-15T12:00:00Z", 44),
        SeedEpisode("cac", "Le premier bug informatique", "2026-09-07T04:30:00Z", 31),
        SeedEpisode("tk", "Kotlin Multiplatform at scale", "2026-08-30T06:00:00Z", 58),
    )

    suspend fun seed(database: ServerDatabase) {
        val accounts = Accounts(database)
        val alice = accounts.create(ALICE, "Alice", ALICE_PASSWORD, Role.ADMIN)
        val bob = accounts.create(BOB, "Bob", BOB_PASSWORD)

        val episodeIds = database.transaction {
            val podcastIds = podcasts.associate { podcast ->
                val id = catalogQueries.insertPodcast(
                    "https://feeds.test/${podcast.key}.xml", podcast.title, podcast.author, podcast.description, null, null,
                ).executeAsOne()
                catalogQueries.markPodcastUnchanged(id)
                podcast.key to id
            }
            // RETURNING makes these queries: they only run when executed.
            podcastIds.values.forEach { catalogQueries.insertSubscription(alice.id, it, null).executeAsOne() }
            catalogQueries.insertSubscription(bob.id, podcastIds.getValue("tk"), "Le podcast officiel de JetBrains").executeAsOne()
            catalogQueries.insertSubscription(bob.id, podcastIds.getValue("frag"), null).executeAsOne()
            // As the subscription paths do: what third-party clients are told (ADR 0011).
            podcasts.forEach { syncQueries.recordSubscriptionChange(alice.id, "https://feeds.test/${it.key}.xml", true) }
            listOf("tk", "frag").forEach { syncQueries.recordSubscriptionChange(bob.id, "https://feeds.test/$it.xml", true) }

            episodes.mapIndexed { index, episode ->
                catalogQueries.insertEpisode(
                    podcastId = podcastIds.getValue(episode.podcast),
                    guid = "seed-${index + 1}",
                    title = episode.title,
                    description = "",
                    publishedAt = Instant.parse(episode.publishedAt).toOffsetDateTime(),
                    durationSeconds = episode.minutes * 60,
                    audioUrl = "https://cdn.test/podcast/seed-${index + 1}.mp3",
                ).executeAsOne()
            }
        }

        // Separate statements so that "updated_at" orders the resume list (last one first).
        database.query {
            fun progress(user: Long, episode: Int, minutes: Int, status: EpisodeStatus) =
                episodesQueries.saveProgress(user, episodeIds[episode - 1], minutes * 60, status.name)

            progress(alice.id, 5, 61, EpisodeStatus.PLAYED)
            progress(alice.id, 4, 25, EpisodeStatus.IN_PROGRESS)
            progress(alice.id, 2, 38, EpisodeStatus.IN_PROGRESS)
            progress(bob.id, 3, 47, EpisodeStatus.PLAYED)
            progress(bob.id, 1, 10, EpisodeStatus.IN_PROGRESS)
        }
    }
}
