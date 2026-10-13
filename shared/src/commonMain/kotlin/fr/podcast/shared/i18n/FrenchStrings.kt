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

    override val signInTitle = "Connexion"
    override val serverUrl = "Adresse du serveur"
    override val username = "Identifiant"
    override val password = "Mot de passe"
    override val signIn = "Se connecter"
    override val signOut = "Se déconnecter"
    override val invalidCredentials = "Identifiant ou mot de passe incorrect."
    override val serverUnreachable = "Impossible de joindre le serveur."
    override val notAPodcastServer = "Cette adresse n'est pas celle d'un serveur de podcasts."
    override val accounts = "Comptes"
    override val addAccount = "Ajouter un compte"
    override val demoMode = "Essayer avec des données de démo"
    override val demoAccount = "Démo (sans serveur)"
    override val cancel = "Annuler"
    override val sessionExpired = "Ce compte n'est plus connecté sur ce serveur."
    override val loadingError = "Impossible de charger les données."
    override val retry = "Réessayer"
    override fun signedInAs(name: String) = "Connecté en tant que $name"

    override fun action(name: String) = when (name) {
        "mark-played" -> "Marquer comme écouté"
        "mark-unplayed" -> "Marquer comme non écouté"
        "update-progress" -> "Enregistrer la position"
        "logout" -> signOut
        else -> name
    }

    override val cliDescription = "Client en ligne de commande du système de podcasts."
    override val cliListDescription = "Affiche l'écran Accueil en texte."
    override val cliTuiDescription = "Ouvre l'écran Accueil en mode interactif."
    override val cliLanguageOption = "Langue de l'interface (fr ou en)."
    override val cliDemoOption = "Utiliser les données de démo au lieu d'un serveur."
    override val cliLoginDescription = "Se connecte à un serveur et enregistre le compte."
    override val cliAccountsDescription = "Liste les comptes enregistrés (* = compte actif)."
    override val cliUseDescription = "Choisit le compte actif."
    override val cliLogoutDescription = "Se déconnecte du compte actif et révoque son jeton."
    override val noAccount = "Aucun compte. Lancez « podcast login » ou ajoutez --demo."
    override val unknownAccount = "Compte inconnu."
    override val signedOut = "Déconnecté."
    override fun accountSaved(username: String, server: String) = "Connecté : $username sur $server."
    override val tuiHelp = "↑/↓ naviguer · q quitter"
}
