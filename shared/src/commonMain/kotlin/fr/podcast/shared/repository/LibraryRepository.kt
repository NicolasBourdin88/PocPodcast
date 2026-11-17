package fr.podcast.shared.repository

import fr.podcast.shared.model.Episode
import fr.podcast.shared.model.OpmlImportReport
import fr.podcast.shared.model.Podcast
import fr.podcast.shared.model.SearchResult
import fr.podcast.shared.model.SubscriptionItem

/** What a user manages besides listening: subscriptions, search, OPML and the server's files. */
interface LibraryRepository {
    /** The user's own subscriptions; those shared with them come from [SharingRepository.sharedWithMe]. */
    suspend fun subscriptionItems(): List<SubscriptionItem>

    /** One of the user's subscriptions, or one shared with them; null otherwise. */
    suspend fun subscription(id: String): SubscriptionItem?

    /** Subscribes to a feed; the server reads it first and refuses what is not a podcast feed. */
    suspend fun subscribe(feedUrl: String): SubscriptionItem
    /** For its owner, or someone it is shared with in [fr.podcast.shared.model.SharePermission.WRITE]. */
    suspend fun updateSubscription(subscriptionId: String, note: String?, autoDownload: Boolean): SubscriptionItem

    /** For its owner only. */
    suspend fun unsubscribe(subscriptionId: String)

    /** Asks the server to read the feed now; returns the podcast with its new refresh state. */
    suspend fun refreshPodcast(podcastId: String): Podcast?
    suspend fun podcastEpisodes(podcastId: String, limit: Int = 50): List<Episode>

    /** Null when the server offers no search. */
    suspend fun search(query: String): List<SearchResult>?

    /** Kept episodes are never purged from the server. */
    suspend fun keep(episodeId: String)
    suspend fun unkeep(episodeId: String)

    /** The user's favorites and ratings (1 to 5; null clears it), which dynamic playlists select on (ADR 0010). */
    suspend fun setFavorite(episodeId: String, favorite: Boolean)
    suspend fun rate(episodeId: String, rating: Int?)

    /** Asks the server to keep a copy of the audio file (shared by every user). */
    suspend fun download(episodeId: String)

    suspend fun exportOpml(): String
    suspend fun importOpml(opml: String): OpmlImportReport
}

/** Everything a client needs from a server, or from the demo data. */
interface PodcastClient : PodcastRepository, LibraryRepository, PlaybackRepository, AccountRepository, SharingRepository, PlaylistRepository
