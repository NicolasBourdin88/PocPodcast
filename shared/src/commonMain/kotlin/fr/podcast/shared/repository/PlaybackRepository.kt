package fr.podcast.shared.repository

import fr.podcast.shared.home.EpisodeItem

/** What playback needs from the server: fresh episodes and the queue ("up next", ADR 0008). */
interface PlaybackRepository {
    /**
     * The episode with its podcast, read again: the position saved by another device and a newly
     * signed audio link ([fr.podcast.shared.model.Episode.audioUrl]).
     */
    @Throws(Exception::class)
    suspend fun episodeItem(id: String): EpisodeItem?

    /** Episodes to play after the current one, in order. */
    @Throws(Exception::class)
    suspend fun queue(): List<EpisodeItem>

    /** At [position] (0 = next), or last when null; an episode already queued moves there. */
    @Throws(Exception::class)
    suspend fun enqueue(episodeId: String, position: Int? = null)
    @Throws(Exception::class)
    suspend fun dequeue(episodeId: String)
    @Throws(Exception::class)
    suspend fun moveInQueue(episodeId: String, position: Int)
    @Throws(Exception::class)
    suspend fun clearQueue()
}
