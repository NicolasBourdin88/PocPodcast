package fr.podcast.shared.i18n

import fr.podcast.shared.model.Episode
import fr.podcast.shared.model.EpisodeStatus
import fr.podcast.shared.model.FileStatus
import fr.podcast.shared.model.PlaylistKind
import fr.podcast.shared.model.PlaylistOrder
import fr.podcast.shared.model.PlaylistRules
import fr.podcast.shared.model.SharePermission
import fr.podcast.shared.model.UserRole
import fr.podcast.shared.navigation.Destination
import kotlinx.datetime.LocalDate

/**
 * Every user-facing string, shared by all clients (Compose, HTML, CLI).
 * Adding a member here is a compile error until every language implements it.
 */
interface Strings {
    val appName: String get() = "Podcast"

    fun destination(destination: Destination): String

    val resumeListening: String
    val latestEpisodes: String
    val queue: String
    val subscriptions: String
    val refresh: String
    val play: String
    val resume: String
    fun comingSoon(screen: String): String

    fun remaining(minutes: Int): String
    fun duration(seconds: Int): String
    fun shortDate(date: LocalDate): String

    val statusNew: String
    val statusPlayed: String

    /** Status shown under an episode: "New", "12 min left" or "Played". */
    fun status(episode: Episode): String = when (episode.status) {
        EpisodeStatus.NEW -> statusNew
        EpisodeStatus.IN_PROGRESS -> remaining(episode.remainingMinutes)
        EpisodeStatus.PLAYED -> statusPlayed
    }

    // Accounts and sign-in
    val signInTitle: String
    val serverUrl: String
    val username: String
    val password: String
    val signIn: String
    val signOut: String
    val invalidCredentials: String
    fun tooManyAttempts(minutes: Int): String
    val serverUnreachable: String
    val notAPodcastServer: String
    val accounts: String
    val addAccount: String
    val demoMode: String
    val demoAccount: String
    val cancel: String
    val sessionExpired: String
    val loadingError: String
    val retry: String
    fun signedInAs(name: String): String

    // Refusals about accounts and shares (ADR 0009)
    val notAllowed: String
    val wrongPassword: String
    fun passwordTooShort(minimum: Int): String
    val usernameUnavailable: String
    fun displayNameInvalid(maximum: Int): String
    val lastAdministrator: String
    val noSuchUser: String
    fun messageTooLong(maximum: Int): String
    fun playlistTitleInvalid(maximum: Int): String
    fun keywordsInvalid(count: Int, length: Int): String

    /** Label of a server action, by its HAL-FORMS template name (ADR 0003). */
    fun action(name: String): String

    // Explore
    val searchPlaceholder: String
    val search: String
    fun noResults(query: String): String
    val addFeedTitle: String
    val feedUrl: String
    val subscribe: String
    val subscribed: String
    fun subscribedTo(title: String): String

    // Library and podcast page
    val emptyLibrary: String
    val note: String
    val notePlaceholder: String
    val save: String
    val autoDownload: String
    val unsubscribe: String
    fun unsubscribeConfirm(title: String): String
    fun unsubscribedFrom(title: String): String
    val episodes: String
    val back: String
    val refreshPending: String
    val refreshFailed: String
    val opmlExport: String
    val opmlImport: String
    fun opmlReport(imported: Int, alreadySubscribed: Int, failed: Int): String
    fun opmlSaved(name: String): String
    val choosePodcast: String
    val noEpisodes: String
    val searchUnavailable: String

    // Files and marks (ADR 0007)
    val kept: String
    val chapters: String
    val transcript: String
    fun fileStatus(status: FileStatus): String?

    // Favorites and ratings (ADR 0010)
    val favorite: String
    val addFavorite: String
    val removeFavorite: String
    val rating: String
    fun rateAs(stars: Int): String
    val clearRating: String

    /** A rating as stars, "★★★★☆" (the same in every language). */
    fun stars(rating: Int): String = rating.coerceIn(0, 5).let { "★".repeat(it) + "☆".repeat(5 - it) }

    // Playlists (ADR 0010)
    val newPlaylist: String
    val playlistTitle: String
    val description: String
    fun playlistKind(kind: PlaylistKind): String
    fun playlistKindHint(kind: PlaylistKind): String
    val keywords: String
    val keywordsHint: String
    val favoritesOnly: String
    val minRating: String
    val anyRating: String
    val unplayedOnly: String
    fun playlistOrder(order: PlaylistOrder): String
    val sortOrder: String
    val episodeLimit: String
    fun episodeCount(count: Int): String
    val playAll: String
    val shuffle: String
    val editPlaylist: String
    val deletePlaylist: String
    fun deletePlaylistConfirm(title: String): String
    val addToPlaylist: String
    fun addedTo(title: String): String
    val noPlaylistToAddTo: String
    val removeFromPlaylist: String
    val emptyPlaylists: String
    val emptyPlaylist: String
    val choosePlaylist: String
    val sharedPlaylists: String
    fun playlistSharedBy(name: String): String
    val feed: String
    val feedHint: String
    val publishFeed: String
    val newFeedAddress: String
    val unpublishFeed: String
    fun feedPublished(title: String): String

    /** A dynamic playlist's rules in a line: "kotlin, ktor · Favorites only · ★★★★☆ · Newest first · ≤ 50". */
    fun playlistRules(rules: PlaylistRules): String = listOfNotNull(
        rules.keywords.joinToString(", ").ifEmpty { null },
        favoritesOnly.takeIf { rules.favoritesOnly },
        stars(rules.minRating).takeIf { rules.minRating > 0 },
        unplayedOnly.takeIf { rules.unplayedOnly },
        playlistOrder(rules.order),
        "≤ ${rules.limit}",
    ).joinToString(" · ")

    // Player (ADR 0008)
    val nowPlaying: String
    val pause: String
    fun skipBack(seconds: Int): String
    fun skipForward(seconds: Int): String

    /** Playback speed: "1.5×", "1,5×" in French. */
    fun speed(value: Float): String
    val next: String
    val close: String
    val emptyQueue: String
    val addToQueue: String
    val removeFromQueue: String
    val playNext: String
    val moveUp: String
    val clearQueue: String
    val playbackFailed: String

    /** Position in an episode: "4:05", "1:02:03" (the same in every language). */
    fun clock(seconds: Int): String {
        val total = seconds.coerceAtLeast(0)
        val hours = total / 3600
        val minutes = total % 3600 / 60
        val rest = (total % 60).toString().padStart(2, '0')
        return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$rest" else "$minutes:$rest"
    }

    // Settings: profile, password, devices, users (ADR 0009)
    val profile: String
    val displayName: String
    fun role(role: UserRole): String
    val profileSaved: String
    val changePassword: String
    val currentPassword: String
    val newPassword: String
    fun passwordHint(minimum: Int): String
    val passwordChanged: String
    val devices: String
    val thisDevice: String
    val noDevices: String
    fun lastUsed(date: LocalDate): String
    val neverUsed: String
    val revokeDevice: String
    fun revokeDeviceConfirm(name: String): String
    val users: String
    val you: String
    val addUser: String
    fun userCreated(name: String): String
    val makeAdmin: String
    val removeAdmin: String
    val resetPassword: String
    fun resetPasswordOf(name: String): String
    val resetPasswordNotice: String
    fun passwordReset(name: String): String
    val deleteUser: String
    fun deleteUserConfirm(name: String): String
    val create: String
    val delete: String
    val moreActions: String

    // Sharing (ADR 0009)
    val sharedWithMe: String
    fun sharedBy(name: String): String
    fun recommendedBy(name: String): String
    fun permission(permission: SharePermission): String

    /** Label of the choice between [permission]s. */
    val sharePermission: String
    val decline: String
    val share: String
    fun shareTitle(title: String): String
    val recommend: String
    fun recommendTitle(title: String): String
    val shareWith: String
    val shareMessage: String
    val sharedWith: String
    val stopSharing: String
    fun sharedWithUser(name: String): String
    fun recommendedTo(name: String): String
    val noOtherUsers: String
    fun ownerNote(name: String): String

    // Server refusals (Problem Details types)
    val feedUnreachable: String
    val notAFeed: String
    val forbiddenFeedAddress: String
    val notAnOpml: String
    val searchFailed: String

    // CLI
    val cliDescription: String
    val cliListDescription: String
    val cliTuiDescription: String
    val cliLanguageOption: String
    val cliDemoOption: String
    val cliLoginDescription: String
    val cliAccountsDescription: String
    val cliUseDescription: String
    val cliLogoutDescription: String
    val noAccount: String
    val unknownAccount: String
    fun unknownNumber(number: Int): String
    val signedOut: String
    fun accountSaved(username: String, server: String): String
    val cliSearchDescription: String
    val cliSubscribeDescription: String
    val cliSubscriptionsDescription: String
    val cliUnsubscribeDescription: String
    val cliNoteDescription: String
    val cliOpmlExportDescription: String
    val cliOpmlImportDescription: String
    val cliPlayDescription: String
    val cliQueueDescription: String
    val cliQueueAddDescription: String
    val cliQueueRemoveDescription: String
    val cliQueueClearDescription: String
    val nothingToPlay: String
    val cliProfileDescription: String
    val cliPasswdDescription: String
    val cliDevicesDescription: String
    val cliDevicesRevokeDescription: String
    val cliUsersDescription: String
    val cliUsersAddDescription: String
    val cliUsersRemoveDescription: String
    val cliUsersPasswdDescription: String
    val cliUsersRoleDescription: String
    val cliSharedDescription: String
    val cliSharedRemoveDescription: String
    val cliShareDescription: String
    val cliWriteOption: String
    val cliUnshareDescription: String
    val cliRecommendDescription: String
    val cliPlaylistsDescription: String
    val cliPlaylistShowDescription: String
    val cliPlaylistCreateDescription: String
    val cliPlaylistEditDescription: String
    val cliDynamicOption: String
    val cliPlaylistAddDescription: String
    val cliPlaylistRemoveDescription: String
    val cliPlaylistMoveDescription: String
    val cliPlaylistDeleteDescription: String
    val cliPlaylistPlayDescription: String
    val cliPlaylistShuffleDescription: String
    val cliPlaylistShareDescription: String
    val cliPlaylistWriteOption: String
    val cliPlaylistUnshareDescription: String
    val cliPlaylistFeedDescription: String
    val cliFavoriteDescription: String
    val cliRateDescription: String
    val rulesOnlyForDynamic: String
    val onlyRandomShuffles: String
    val playlistNotShared: String
    fun playlistNoLongerSharedWith(name: String): String
    fun playlistDeleted(title: String): String
    val feedUnpublished: String
    fun favoriteAdded(title: String): String
    fun favoriteRemoved(title: String): String
    fun ratingCleared(title: String): String
    val confirmPassword: String
    val passwordsDiffer: String
    val confirmWithYes: String
    val notShared: String
    val nothingShared: String
    fun noLongerSharedWith(name: String): String
    val shareRemoved: String
    fun deviceSignedOut(name: String): String
    fun userDeleted(name: String): String
    val tuiHelp: String
    val tuiPlayerHelp: String
}
