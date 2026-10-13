package fr.podcast.shared.repository

import fr.podcast.shared.model.Episode
import fr.podcast.shared.model.Podcast

/** Podcasts and episodes as seen by the current user. */
interface PodcastRepository {
    suspend fun subscriptions(): List<Podcast>
    suspend fun podcast(id: String): Podcast?
    suspend fun episode(id: String): Episode?
    suspend fun latestEpisodes(limit: Int): List<Episode>
    suspend fun inProgress(): List<Episode>

    /** Saves the listening position; the episode becomes in progress. */
    suspend fun updateProgress(episodeId: String, positionSeconds: Int)
    suspend fun markPlayed(episodeId: String)
    suspend fun markUnplayed(episodeId: String)
}
