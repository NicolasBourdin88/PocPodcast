package fr.podcast.shared.repository

import fr.podcast.shared.model.Episode
import fr.podcast.shared.model.EpisodeStatus
import fr.podcast.shared.model.Podcast
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant

/** In-memory data, used by the demo mode and the tests. */
class FakePodcastRepository : PodcastRepository {

    private val mutex = Mutex()

    private val podcasts = listOf(
        Podcast("p1", "Les Cast Codeurs", "Emmanuel Bernard & co", "https://lescastcodeurs.libsyn.com/rss"),
        Podcast("p2", "Talking Kotlin", "JetBrains", "https://talkingkotlin.com/feed.xml"),
        Podcast("p3", "Le Code a changé", "France Inter", "https://radiofrance-podcast.net/podcast09/rss_20786.xml"),
        Podcast("p4", "Fragmented", "Donn Felker & Kaushik Gopal", "https://feeds.simplecast.com/LpAGSLnY"),
    )

    // Ordered from most recent to oldest.
    private val episodes = mutableListOf(
        Episode("e1", "p2", "Compose Multiplatform for iOS is stable", Instant.parse("2026-10-12T06:00:00Z"), durationSeconds = 52 * 60),
        Episode(
            "e2", "p1", "LCC 330 - Les nouveautés de Kotlin 2.4", Instant.parse("2026-10-10T05:00:00Z"),
            durationSeconds = 95 * 60, positionSeconds = 38 * 60, status = EpisodeStatus.IN_PROGRESS,
        ),
        Episode("e3", "p4", "KMP in production", Instant.parse("2026-10-08T12:00:00Z"), durationSeconds = 47 * 60),
        Episode(
            "e4", "p3", "Qui a inventé le copier-coller ?", Instant.parse("2026-10-05T04:30:00Z"),
            durationSeconds = 34 * 60, positionSeconds = 25 * 60, status = EpisodeStatus.IN_PROGRESS,
        ),
        Episode(
            "e5", "p2", "Ktor 3 and server-side Kotlin", Instant.parse("2026-09-28T06:00:00Z"),
            durationSeconds = 61 * 60, positionSeconds = 61 * 60, status = EpisodeStatus.PLAYED,
        ),
    )

    override suspend fun subscriptions(): List<Podcast> = podcasts

    override suspend fun podcast(id: String): Podcast? = podcasts.find { it.id == id }

    override suspend fun episode(id: String): Episode? = mutex.withLock { episodes.find { it.id == id } }

    override suspend fun latestEpisodes(limit: Int): List<Episode> = mutex.withLock { episodes.take(limit) }

    override suspend fun inProgress(): List<Episode> =
        mutex.withLock { episodes.filter { it.status == EpisodeStatus.IN_PROGRESS } }

    override suspend fun updateProgress(episodeId: String, positionSeconds: Int) =
        update(episodeId) { it.copy(positionSeconds = positionSeconds, status = EpisodeStatus.IN_PROGRESS) }

    override suspend fun markPlayed(episodeId: String) =
        update(episodeId) { it.copy(positionSeconds = it.durationSeconds, status = EpisodeStatus.PLAYED) }

    override suspend fun markUnplayed(episodeId: String) =
        update(episodeId) { it.copy(positionSeconds = 0, status = EpisodeStatus.NEW) }

    private suspend fun update(episodeId: String, change: (Episode) -> Episode) = mutex.withLock {
        val index = episodes.indexOfFirst { it.id == episodeId }
        if (index >= 0) episodes[index] = change(episodes[index])
    }
}
