package fr.podcast.shared.repository

import fr.podcast.shared.model.Episode
import fr.podcast.shared.model.Podcast

/** Podcasts and episodes as seen by the current user. */
interface PodcastRepository {
    @Throws(Exception::class)
    suspend fun subscriptions(): List<Podcast>
    @Throws(Exception::class)
    suspend fun podcast(id: String): Podcast?
    @Throws(Exception::class)
    suspend fun episode(id: String): Episode?
    @Throws(Exception::class)
    suspend fun latestEpisodes(limit: Int): List<Episode>
    @Throws(Exception::class)
    suspend fun inProgress(): List<Episode>

    /** Saves the listening position; the episode becomes in progress. */
    @Throws(Exception::class)
    suspend fun updateProgress(episodeId: String, positionSeconds: Int)
    @Throws(Exception::class)
    suspend fun markPlayed(episodeId: String)
    @Throws(Exception::class)
    suspend fun markUnplayed(episodeId: String)
}
