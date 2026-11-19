package fr.podcast.shared.repository

import fr.podcast.shared.model.Episode
import fr.podcast.shared.model.EpisodePage
import fr.podcast.shared.model.EpisodeQuery
import fr.podcast.shared.model.ListeningStats
import fr.podcast.shared.model.OpmlImportReport
import fr.podcast.shared.model.Podcast
import fr.podcast.shared.model.SearchResult
import fr.podcast.shared.model.SubscriptionItem

/** What a user manages besides listening: subscriptions, search, OPML and the server's files. */
interface LibraryRepository {
    /** The user's own subscriptions; those shared with them come from [SharingRepository.sharedWithMe]. */
    @Throws(Exception::class)
    suspend fun subscriptionItems(): List<SubscriptionItem>

    /** One of the user's subscriptions, or one shared with them; null otherwise. */
    @Throws(Exception::class)
    suspend fun subscription(id: String): SubscriptionItem?

    /** Subscribes to a feed; the server reads it first and refuses what is not a podcast feed. */
    @Throws(Exception::class)
    suspend fun subscribe(feedUrl: String): SubscriptionItem
    /**
     * For its owner, or someone it is shared with in [fr.podcast.shared.model.SharePermission.WRITE].
     * [tags] null keeps them (ADR 0012).
     */
    @Throws(Exception::class)
    suspend fun updateSubscription(subscriptionId: String, note: String?, autoDownload: Boolean, tags: List<String>? = null): SubscriptionItem

    /** For its owner only. */
    @Throws(Exception::class)
    suspend fun unsubscribe(subscriptionId: String)

    /** Asks the server to read the feed now; returns the podcast with its new refresh state. */
    @Throws(Exception::class)
    suspend fun refreshPodcast(podcastId: String): Podcast?
    @Throws(Exception::class)
    suspend fun podcastEpisodes(podcastId: String, limit: Int = 50): List<Episode>

    /** Null when the server offers no search. */
    @Throws(Exception::class)
    suspend fun search(query: String): List<SearchResult>?

    /** Kept episodes are never purged from the server. */
    @Throws(Exception::class)
    suspend fun keep(episodeId: String)
    @Throws(Exception::class)
    suspend fun unkeep(episodeId: String)

    /** The user's favorites and ratings (1 to 5; null clears it), which dynamic playlists select on (ADR 0010). */
    @Throws(Exception::class)
    suspend fun setFavorite(episodeId: String, favorite: Boolean)
    @Throws(Exception::class)
    suspend fun rate(episodeId: String, rating: Int?)

    /** Asks the server to keep a copy of the audio file (shared by every user). */
    @Throws(Exception::class)
    suspend fun download(episodeId: String)

    @Throws(Exception::class)
    suspend fun exportOpml(): String
    @Throws(Exception::class)
    suspend fun importOpml(opml: String): OpmlImportReport

    /** The episodes of the user's subscriptions that match [query], newest first (ADR 0012). */
    @Throws(Exception::class)
    suspend fun searchEpisodes(query: EpisodeQuery, limit: Int = 50, offset: Int = 0): EpisodePage

    /** How much the user listened, and to what (ADR 0012). */
    @Throws(Exception::class)
    suspend fun listeningStats(): ListeningStats
}

/** Everything a client needs from a server, or from the demo data. */
interface PodcastClient : PodcastRepository, LibraryRepository, PlaybackRepository, AccountRepository, SharingRepository, PlaylistRepository
