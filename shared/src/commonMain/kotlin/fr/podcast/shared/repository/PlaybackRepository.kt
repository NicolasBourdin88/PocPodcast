package fr.podcast.shared.repository

import fr.podcast.shared.home.EpisodeItem

/** What playback needs from the server: fresh episodes and the queue ("up next", ADR 0008). */
interface PlaybackRepository {
    /**
     * The episode with its podcast, read again: the position saved by another device and a newly
     * signed audio link ([fr.podcast.shared.model.Episode.audioUrl]).
     */
    suspend fun episodeItem(id: String): EpisodeItem?

    /** Episodes to play after the current one, in order. */
    suspend fun queue(): List<EpisodeItem>

    /** At [position] (0 = next), or last when null; an episode already queued moves there. */
    suspend fun enqueue(episodeId: String, position: Int? = null)
    suspend fun dequeue(episodeId: String)
    suspend fun moveInQueue(episodeId: String, position: Int)
    suspend fun clearQueue()
}
