package fr.podcast.shared.i18n

import fr.podcast.shared.model.Episode
import fr.podcast.shared.model.EpisodeStatus
import fr.podcast.shared.model.FileStatus
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
    val tuiHelp: String
    val tuiPlayerHelp: String
}
