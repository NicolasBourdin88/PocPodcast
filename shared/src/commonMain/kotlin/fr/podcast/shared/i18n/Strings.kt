package fr.podcast.shared.i18n

import fr.podcast.shared.model.Episode
import fr.podcast.shared.model.EpisodeStatus
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

    // CLI
    val cliDescription: String
    val cliListDescription: String
    val cliTuiDescription: String
    val cliLanguageOption: String
    val tuiHelp: String
}
