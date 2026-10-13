package fr.podcast.shared.i18n

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
    override val invalidCredentials = "Wrong username or password."
    override val serverUnreachable = "Cannot reach the server."
    override val notAPodcastServer = "This address is not a podcast server."
    override val accounts = "Accounts"
    override val addAccount = "Add an account"
    override val demoMode = "Try with demo data"
    override val demoAccount = "Demo (no server)"
    override val loadingError = "Could not load the data."
    override val retry = "Retry"
    override fun signedInAs(name: String) = "Signed in as $name"

    override fun action(name: String) = when (name) {
        "mark-played" -> "Mark as played"
        "mark-unplayed" -> "Mark as unplayed"
        "update-progress" -> "Save position"
        "logout" -> signOut
        else -> name
    }

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
    override val unknownAccount = "Unknown account."
    override val signedOut = "Signed out."
    override fun accountSaved(username: String, server: String) = "Signed in: $username on $server."
    override val tuiHelp = "↑/↓ move · q quit"
}
