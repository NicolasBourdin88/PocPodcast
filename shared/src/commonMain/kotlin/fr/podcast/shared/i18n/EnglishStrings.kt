package fr.podcast.shared.i18n

import fr.podcast.shared.model.FileStatus
import fr.podcast.shared.navigation.Destination
import kotlinx.datetime.LocalDate

object EnglishStrings : Strings {
    override fun destination(destination: Destination) = when (destination) {
        Destination.Home -> "Home"
        Destination.Explore -> "Explore"
        Destination.Library -> "Library"
        Destination.Settings -> "Settings"
    }

    override val resumeListening = "Continue listening"
    override val latestEpisodes = "New episodes"
    override val queue = "Up next"
    override val subscriptions = "Subscriptions"
    override val refresh = "Refresh"
    override val play = "Play"
    override val resume = "Resume"
    override fun comingSoon(screen: String) = "$screen — coming soon"

    override fun remaining(minutes: Int) = "$minutes min left"

    override fun duration(seconds: Int): String {
        val minutes = seconds / 60
        return if (minutes >= 60) "${minutes / 60} hr ${minutes % 60} min" else "$minutes min"
    }

    private val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    override fun shortDate(date: LocalDate) = "${months[date.month.ordinal]} ${date.day}"

    override val statusNew = "New"
    override val statusPlayed = "Played"

    override val signInTitle = "Sign in"
    override val serverUrl = "Server address"
    override val username = "Username"
    override val password = "Password"
    override val signIn = "Sign in"
    override val signOut = "Sign out"
    override fun tooManyAttempts(minutes: Int) = "Too many attempts: try again in $minutes min."
    override val invalidCredentials = "Wrong username or password."
    override val serverUnreachable = "Cannot reach the server."
    override val notAPodcastServer = "This address is not a podcast server."
    override val accounts = "Accounts"
    override val addAccount = "Add an account"
    override val demoMode = "Try with demo data"
    override val demoAccount = "Demo (no server)"
    override val cancel = "Cancel"
    override val sessionExpired = "This account is no longer signed in on this server."
    override val loadingError = "Could not load the data."
    override val retry = "Retry"
    override fun signedInAs(name: String) = "Signed in as $name"

    override fun action(name: String) = when (name) {
        "mark-played" -> "Mark as played"
        "mark-unplayed" -> "Mark as unplayed"
        "update-progress" -> "Save position"
        "keep" -> "Keep"
        "unkeep" -> "Stop keeping"
        "download" -> "Download to the server"
        "subscribe" -> subscribe
        "unsubscribe" -> unsubscribe
        "update" -> save
        "refresh" -> "Read the feed again"
        "logout" -> signOut
        "enqueue" -> addToQueue
        "dequeue" -> removeFromQueue
        "move" -> "Move in the queue"
        "clear-queue" -> clearQueue
        else -> name
    }

    override val searchPlaceholder = "Search podcasts"
    override val search = "Search"
    override fun noResults(query: String) = "No results for \"$query\"."
    override val addFeedTitle = "Add an RSS feed"
    override val feedUrl = "Feed address"
    override val subscribe = "Subscribe"
    override val subscribed = "Subscribed"
    override fun subscribedTo(title: String) = "Subscribed to \"$title\"."

    override val emptyLibrary = "No subscriptions yet. Find a podcast in Explore."
    override val note = "Note"
    override val notePlaceholder = "Add a note…"
    override val save = "Save"
    override val autoDownload = "Download new episodes to the server automatically"
    override val unsubscribe = "Unsubscribe"
    override fun unsubscribeConfirm(title: String) = "Unsubscribe from \"$title\"?"
    override fun unsubscribedFrom(title: String) = "Unsubscribed from \"$title\"."
    override val episodes = "Episodes"
    override val back = "Back"
    override val refreshPending = "Reading the feed…"
    override val refreshFailed = "The feed could not be read."
    override val opmlExport = "Export (OPML)"
    override val opmlImport = "Import an OPML file"
    override fun opmlReport(imported: Int, alreadySubscribed: Int, failed: Int) =
        "$imported imported, $alreadySubscribed already followed, $failed failed."
    override fun opmlSaved(name: String) = "Subscriptions exported to $name."
    override val choosePodcast = "Choose a podcast in the list."
    override val noEpisodes = "No episodes yet."
    override val searchUnavailable = "This server has no search; add a feed by its address."

    override val kept = "Kept"
    override val chapters = "Chapters"
    override val transcript = "Transcript"
    override fun fileStatus(status: FileStatus) = when (status) {
        FileStatus.NONE -> null
        FileStatus.PENDING, FileStatus.DOWNLOADING -> "Downloading…"
        FileStatus.READY -> "On the server"
        FileStatus.FAILED -> "Download failed"
    }

    override val nowPlaying = "Now playing"
    override val pause = "Pause"
    override fun skipBack(seconds: Int) = "Back $seconds s"
    override fun skipForward(seconds: Int) = "Forward $seconds s"
    override fun speed(value: Float) = (if (value % 1f == 0f) value.toInt().toString() else value.toString()) + "×"
    override val next = "Next episode"
    override val close = "Close"
    override val emptyQueue = "The queue is empty."
    override val addToQueue = "Add to queue"
    override val removeFromQueue = "Remove from queue"
    override val playNext = "Play next"
    override val moveUp = "Move up"
    override val clearQueue = "Clear queue"
    override val playbackFailed = "This episode cannot be played."

    override val feedUnreachable = "This feed cannot be read."
    override val notAFeed = "This address is not a podcast feed."
    override val forbiddenFeedAddress = "This address is not allowed."
    override val notAnOpml = "This file is not a readable OPML."
    override val searchFailed = "The search did not succeed."

    override val cliDescription = "Command-line client for the podcast system."
    override val cliListDescription = "Prints the Home screen as text."
    override val cliTuiDescription = "Opens the Home screen in interactive mode."
    override val cliLanguageOption = "Interface language (fr or en)."
    override val cliDemoOption = "Use demo data instead of a server."
    override val cliLoginDescription = "Signs in to a server and saves the account."
    override val cliAccountsDescription = "Lists saved accounts (* = active account)."
    override val cliUseDescription = "Selects the active account."
    override val cliLogoutDescription = "Signs out of the active account and revokes its token."
    override val noAccount = "No account. Run \"podcast login\" or add --demo."
    override fun unknownNumber(number: Int) = "No number $number in the list."
    override val unknownAccount = "Unknown account."
    override val signedOut = "Signed out."
    override fun accountSaved(username: String, server: String) = "Signed in: $username on $server."
    override val cliSearchDescription = "Searches podcasts (Apple Podcasts, Podcast Index)."
    override val cliSubscribeDescription = "Subscribes to a feed: RSS address or number from the last search."
    override val cliSubscriptionsDescription = "Lists subscriptions."
    override val cliUnsubscribeDescription = "Unsubscribes (number from the subscriptions list)."
    override val cliNoteDescription = "Annotates a subscription; without text, clears the note."
    override val cliOpmlExportDescription = "Exports subscriptions as OPML."
    override val cliOpmlImportDescription = "Imports an OPML file."
    override val cliPlayDescription = "Plays an episode in the terminal: its number in `list`, otherwise the one to resume or the first of the queue."
    override val cliQueueDescription = "Shows the queue."
    override val cliQueueAddDescription = "Adds an episode (number in `list`) to the queue; --next to play it next."
    override val cliQueueRemoveDescription = "Removes an episode from the queue (number in the queue)."
    override val cliQueueClearDescription = "Clears the queue."
    override val nothingToPlay = "Nothing to play."
    override val tuiHelp = "↑/↓ move · enter play · a queue · q quit"
    override val tuiPlayerHelp = "space play/pause · ←/→ −15 s/+30 s · +/− speed · n next"
}
