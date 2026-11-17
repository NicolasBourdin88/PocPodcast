package fr.podcast.shared.repository

import fr.podcast.shared.home.EpisodeItem
import fr.podcast.shared.model.Playlist
import fr.podcast.shared.model.Share
import fr.podcast.shared.model.SharePermission
import fr.podcast.shared.model.SubscriptionItem

/** Something shared with the user, with what it shares: a subscription, an episode or a playlist. */
data class SharedItem(
    val share: Share,
    val subscription: SubscriptionItem? = null,
    val episode: EpisodeItem? = null,
    val playlist: Playlist? = null,
)

/** Shares between the accounts of one server (ADR 0009). */
interface SharingRepository {
    /** What others share with the user, most recent first. */
    suspend fun sharedWithMe(): List<SharedItem>

    /** Whom one of the user's subscriptions is shared with. */
    suspend fun sharesOf(subscriptionId: String): List<Share>

    /** Sharing again with the same person replaces the permission and the message. */
    suspend fun shareSubscription(
        subscriptionId: String,
        userId: String,
        permission: SharePermission = SharePermission.READ,
        message: String? = null,
    ): Share

    /** Shares a playlist: [SharePermission.WRITE] lets them change it too, never delete or share it. */
    suspend fun sharePlaylist(
        playlistId: String,
        userId: String,
        permission: SharePermission = SharePermission.READ,
        message: String? = null,
    ): Share

    /** Whom one of the user's playlists is shared with. */
    suspend fun sharesOfPlaylist(playlistId: String): List<Share>

    /** A recommendation is a share of an episode, always [SharePermission.READ]. */
    suspend fun recommend(episodeId: String, userId: String, message: String? = null): Share

    /** By the owner only, and not on a recommendation. */
    suspend fun changePermission(shareId: String, permission: SharePermission): Share

    /** The owner takes the share back, or the grantee declines it. */
    suspend fun unshare(shareId: String)
}
