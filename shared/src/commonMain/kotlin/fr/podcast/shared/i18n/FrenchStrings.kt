package fr.podcast.shared.i18n

import fr.podcast.shared.navigation.Destination
import kotlinx.datetime.LocalDate

object FrenchStrings : Strings {
    override fun destination(destination: Destination) = when (destination) {
        Destination.Home -> "Accueil"
        Destination.Explore -> "Explorer"
        Destination.Library -> "Bibliothèque"
        Destination.Settings -> "Réglages"
    }

    override val resumeListening = "Reprendre l'écoute"
    override val latestEpisodes = "Nouveaux épisodes"
    override val queue = "File d'attente"
    override val subscriptions = "Abonnements"
    override val refresh = "Rafraîchir"
    override val play = "Lire"
    override val resume = "Reprendre"
    override fun comingSoon(screen: String) = "$screen — à venir"

    override fun remaining(minutes: Int) = if (minutes <= 1) "$minutes min restante" else "$minutes min restantes"

    override fun duration(seconds: Int): String {
        val minutes = seconds / 60
        return if (minutes >= 60) "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')}" else "$minutes min"
    }

    private val months = listOf(
        "janv.", "févr.", "mars", "avr.", "mai", "juin", "juil.", "août", "sept.", "oct.", "nov.", "déc.",
    )

    override fun shortDate(date: LocalDate) = "${date.day} ${months[date.month.ordinal]}"

    override val statusNew = "Nouveau"
    override val statusPlayed = "Écouté"

    override val cliDescription = "Client en ligne de commande du système de podcasts."
    override val cliListDescription = "Affiche l'écran Accueil en texte."
    override val cliTuiDescription = "Ouvre l'écran Accueil en mode interactif."
    override val cliLanguageOption = "Langue de l'interface (fr ou en)."
    override val tuiHelp = "↑/↓ naviguer · q quitter"
}
