package fr.podcast.shared.repository

import fr.podcast.shared.model.Episode
import fr.podcast.shared.model.EpisodeStatus
import fr.podcast.shared.model.Podcast
import kotlinx.datetime.LocalDate

/** In-memory data used by every client until the real backend exists (step 3). */
class FakePodcastRepository : PodcastRepository {

    private val podcasts = listOf(
        Podcast("p1", "Les Cast Codeurs", "Emmanuel Bernard & co", "https://lescastcodeurs.libsyn.com/rss"),
        Podcast("p2", "Talking Kotlin", "JetBrains", "https://talkingkotlin.com/feed.xml"),
        Podcast("p3", "Le Code a changé", "France Inter", "https://radiofrance-podcast.net/podcast09/rss_20786.xml"),
        Podcast("p4", "Fragmented", "Donn Felker & Kaushik Gopal", "https://feeds.simplecast.com/LpAGSLnY"),
    )

    // Ordered from most recent to oldest.
    private val episodes = listOf(
        Episode("e1", "p2", "Compose Multiplatform for iOS is stable", LocalDate(2026, 10, 12), durationSeconds = 52 * 60),
        Episode(
            "e2", "p1", "LCC 330 - Les nouveautés de Kotlin 2.4", LocalDate(2026, 10, 10),
            durationSeconds = 95 * 60, positionSeconds = 38 * 60, status = EpisodeStatus.IN_PROGRESS,
        ),
        Episode("e3", "p4", "KMP in production", LocalDate(2026, 10, 8), durationSeconds = 47 * 60),
        Episode(
            "e4", "p3", "Qui a inventé le copier-coller ?", LocalDate(2026, 10, 5),
            durationSeconds = 34 * 60, positionSeconds = 25 * 60, status = EpisodeStatus.IN_PROGRESS,
        ),
        Episode(
            "e5", "p2", "Ktor 3 and server-side Kotlin", LocalDate(2026, 9, 28),
            durationSeconds = 61 * 60, positionSeconds = 61 * 60, status = EpisodeStatus.PLAYED,
        ),
    )

    override suspend fun subscriptions(): List<Podcast> = podcasts

    override suspend fun podcast(id: String): Podcast? = podcasts.find { it.id == id }

    override suspend fun latestEpisodes(limit: Int): List<Episode> = episodes.take(limit)

    override suspend fun inProgress(): List<Episode> = episodes.filter { it.status == EpisodeStatus.IN_PROGRESS }
}
