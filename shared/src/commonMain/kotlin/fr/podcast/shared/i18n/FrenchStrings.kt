package fr.podcast.shared.i18n

import fr.podcast.shared.model.FileStatus
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
        "keep" -> "Garder"
        "unkeep" -> "Ne plus garder"
        "download" -> "Télécharger sur le serveur"
        "subscribe" -> subscribe
        "unsubscribe" -> unsubscribe
        "update" -> save
        "refresh" -> "Relire le flux"
        "logout" -> signOut
        else -> name
    }

    override val searchPlaceholder = "Rechercher un podcast"
    override val search = "Rechercher"
    override fun noResults(query: String) = "Aucun résultat pour « $query »."
    override val addFeedTitle = "Ajouter un flux RSS"
    override val feedUrl = "Adresse du flux"
    override val subscribe = "S'abonner"
    override val subscribed = "Abonné"
    override fun subscribedTo(title: String) = "Abonné à « $title »."

    override val emptyLibrary = "Pas encore d'abonnement. Cherchez un podcast dans Explorer."
    override val note = "Note"
    override val notePlaceholder = "Ajouter une note…"
    override val save = "Enregistrer"
    override val autoDownload = "Télécharger automatiquement les nouveaux épisodes sur le serveur"
    override val unsubscribe = "Se désabonner"
    override fun unsubscribeConfirm(title: String) = "Se désabonner de « $title » ?"
    override fun unsubscribedFrom(title: String) = "Désabonné de « $title »."
    override val episodes = "Épisodes"
    override val back = "Retour"
    override val refreshPending = "Lecture du flux en cours…"
    override val refreshFailed = "Le flux n'a pas pu être lu."
    override val opmlExport = "Exporter (OPML)"
    override val opmlImport = "Importer un fichier OPML"
    override fun opmlReport(imported: Int, alreadySubscribed: Int, failed: Int) =
        "$imported importé(s), $alreadySubscribed déjà suivi(s), $failed en erreur."
    override fun opmlSaved(name: String) = "Abonnements exportés dans $name."
    override val choosePodcast = "Choisissez un podcast dans la liste."
    override val noEpisodes = "Aucun épisode pour l'instant."
    override val searchUnavailable = "Ce serveur ne propose pas de recherche ; ajoutez un flux par son adresse."

    override val kept = "Gardé"
    override val chapters = "Chapitres"
    override val transcript = "Transcription"
    override fun fileStatus(status: FileStatus) = when (status) {
        FileStatus.NONE -> null
        FileStatus.PENDING, FileStatus.DOWNLOADING -> "Téléchargement…"
        FileStatus.READY -> "Sur le serveur"
        FileStatus.FAILED -> "Échec du téléchargement"
    }

    override val feedUnreachable = "Impossible de lire ce flux."
    override val notAFeed = "Cette adresse n'est pas un flux de podcast."
    override val forbiddenFeedAddress = "Cette adresse n'est pas autorisée."
    override val notAnOpml = "Ce fichier n'est pas un OPML lisible."
    override val searchFailed = "La recherche n'a pas abouti."

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
    override fun unknownNumber(number: Int) = "Pas de n° $number dans la liste."
    override val unknownAccount = "Compte inconnu."
    override val signedOut = "Déconnecté."
    override fun accountSaved(username: String, server: String) = "Connecté : $username sur $server."
    override val cliSearchDescription = "Cherche des podcasts (Apple Podcasts, Podcast Index)."
    override val cliSubscribeDescription = "S'abonne à un flux : adresse RSS ou numéro de la dernière recherche."
    override val cliSubscriptionsDescription = "Liste les abonnements."
    override val cliUnsubscribeDescription = "Se désabonne (numéro de la liste des abonnements)."
    override val cliNoteDescription = "Annote un abonnement ; sans texte, efface la note."
    override val cliOpmlExportDescription = "Exporte les abonnements en OPML."
    override val cliOpmlImportDescription = "Importe un fichier OPML."
    override val tuiHelp = "↑/↓ naviguer · q quitter"
}
