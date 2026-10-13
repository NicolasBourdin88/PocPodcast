package fr.podcast.shared.repository

import fr.podcast.shared.model.Episode
import fr.podcast.shared.model.Podcast

interface PodcastRepository {
    suspend fun subscriptions(): List<Podcast>
    suspend fun podcast(id: String): Podcast?
    suspend fun latestEpisodes(limit: Int): List<Episode>
    suspend fun inProgress(): List<Episode>
}
