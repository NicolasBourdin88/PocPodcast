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

    override val cliDescription = "Command-line client for the podcast system."
    override val cliListDescription = "Prints the Home screen as text."
    override val cliTuiDescription = "Opens the Home screen in interactive mode."
    override val cliLanguageOption = "Interface language (fr or en)."
    override val tuiHelp = "↑/↓ move · q quit"
}
