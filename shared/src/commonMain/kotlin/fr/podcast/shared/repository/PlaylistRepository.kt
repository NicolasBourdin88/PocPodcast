package fr.podcast.shared.repository

import fr.podcast.shared.home.EpisodeItem
import fr.podcast.shared.model.Playlist
import fr.podcast.shared.model.PlaylistRules

/** A playlist with its episodes, each with the user's own state. */
data class PlaylistDetails(val playlist: Playlist, val episodes: List<EpisodeItem>)

/**
 * Playlists (ADR 0010): static ones keep the episodes in the order chosen, dynamic ones are computed
 * from their rules on their owner's library.
 */
interface PlaylistRepository {
    /** The user's own playlists, by title; those shared with them come from [SharingRepository.sharedWithMe]. */
    @Throws(Exception::class)
    suspend fun playlists(): List<Playlist>

    /** One of the user's playlists or one shared with them; null otherwise. */
    @Throws(Exception::class)
    suspend fun playlist(id: String): PlaylistDetails?

    /** A static playlist without [rules], a dynamic one with them. */
    @Throws(Exception::class)
    suspend fun createPlaylist(title: String, description: String? = null, rules: PlaylistRules? = null): PlaylistDetails

    /** The kind does not change: [rules] only apply to a dynamic playlist. */
    @Throws(Exception::class)
    suspend fun updatePlaylist(id: String, title: String, description: String?, rules: PlaylistRules? = null): PlaylistDetails

    /** By its owner only. */
    @Throws(Exception::class)
    suspend fun deletePlaylist(id: String)

    /** For a static playlist: at [position] (0 = first), or last; an episode already there moves. */
    @Throws(Exception::class)
    suspend fun addToPlaylist(playlistId: String, episodeId: String, position: Int? = null)
    @Throws(Exception::class)
    suspend fun removeFromPlaylist(playlistId: String, episodeId: String)
    @Throws(Exception::class)
    suspend fun moveInPlaylist(playlistId: String, episodeId: String, position: Int)

    /** A new order for a dynamic playlist in random order. */
    @Throws(Exception::class)
    suspend fun shufflePlaylist(id: String): PlaylistDetails

    /** Publishes its RSS feed under a new address, returned; the previous address stops working. By its owner. */
    @Throws(Exception::class)
    suspend fun publishFeed(playlistId: String): String
    @Throws(Exception::class)
    suspend fun unpublishFeed(playlistId: String)
}
