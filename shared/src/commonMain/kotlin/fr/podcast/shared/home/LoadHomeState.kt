package fr.podcast.shared.home

import fr.podcast.shared.model.Episode
import fr.podcast.shared.model.Podcast
import fr.podcast.shared.repository.PodcastRepository
import kotlinx.serialization.Serializable

/** An episode with its podcast, so every client can render it without another lookup. */
@Serializable
data class EpisodeItem(val episode: Episode, val podcast: Podcast)

/** Home screen state, shared by every client: only the rendering differs. */
@Serializable
data class HomeState(
    val resume: List<EpisodeItem>,
    val latest: List<EpisodeItem>,
)

class LoadHomeState(
    private val repository: PodcastRepository,
    private val latestLimit: Int = DEFAULT_LATEST_LIMIT,
) {
    suspend operator fun invoke(): HomeState {
        val podcasts = repository.subscriptions().associateBy { it.id }
        fun List<Episode>.toItems() = mapNotNull { episode ->
            podcasts[episode.podcastId]?.let { EpisodeItem(episode, it) }
        }
        return HomeState(
            resume = repository.inProgress().toItems(),
            latest = repository.latestEpisodes(latestLimit).toItems(),
        )
    }

    companion object {
        const val DEFAULT_LATEST_LIMIT = 10
    }
}
