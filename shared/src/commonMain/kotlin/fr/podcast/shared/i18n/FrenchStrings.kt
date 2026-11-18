package fr.podcast.shared.i18n

import fr.podcast.shared.model.FileStatus
import fr.podcast.shared.model.PlaylistKind
import fr.podcast.shared.model.PlaylistOrder
import fr.podcast.shared.model.SharePermission
import fr.podcast.shared.model.UserRole
import fr.podcast.shared.navigation.Destination
import kotlinx.datetime.LocalDate

object FrenchStrings : Strings {
    override fun destination(destination: Destination) = when (destination) {
        Destination.Home -> "Accueil"
        Destination.Explore -> "Explorer"
        Destination.Library -> "Bibliothèque"
        Destination.Playlists -> "Playlists"
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
    override fun tooManyAttempts(minutes: Int) = "Trop d'essais : réessayez dans $minutes min."
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

    override val notAllowed = "Vous n'avez pas le droit de faire cela."
    override val wrongPassword = "Le mot de passe actuel est incorrect."
    override fun passwordTooShort(minimum: Int) = "Le mot de passe doit contenir au moins $minimum caractères."
    override val usernameUnavailable = "Identifiant invalide ou déjà pris : de 3 à 32 caractères parmi a-z, 0-9, « . », « _ » et « - »."
    override fun displayNameInvalid(maximum: Int) = "Le nom affiché doit contenir de 1 à $maximum caractères."
    override val lastAdministrator = "Le serveur doit garder au moins un administrateur."
    override val noSuchUser = "Ce compte n'existe pas."
    override fun messageTooLong(maximum: Int) = "Le message est trop long ($maximum caractères au plus)."
    override fun playlistTitleInvalid(maximum: Int) = "Le titre doit contenir de 1 à $maximum caractères."
    override fun keywordsInvalid(count: Int, length: Int) = "Au plus $count mots-clés, de $length caractères au plus chacun."

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
        "enqueue" -> addToQueue
        "dequeue" -> removeFromQueue
        "move" -> "Déplacer dans la file"
        "clear-queue" -> clearQueue
        "share" -> share
        "unshare" -> stopSharing
        "recommend" -> recommend
        "change-permission" -> "Changer les droits"
        "update-profile" -> "Enregistrer le profil"
        "change-password" -> changePassword
        "create-user" -> addUser
        "update-user" -> "Modifier le compte"
        "reset-password" -> resetPassword
        "delete-user" -> deleteUser
        "revoke" -> revokeDevice
        "favorite" -> addFavorite
        "unfavorite" -> removeFavorite
        "rate" -> "Noter"
        "clear-rating" -> clearRating
        "create-playlist" -> newPlaylist
        "update-playlist" -> editPlaylist
        "delete-playlist" -> deletePlaylist
        "add-episode" -> addToPlaylist
        "remove-from-playlist" -> removeFromPlaylist
        "move-in-playlist" -> "Déplacer dans la playlist"
        "shuffle" -> shuffle
        "publish-feed" -> publishFeed
        "unpublish-feed" -> unpublishFeed
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

    override val favorite = "Favori"
    override val addFavorite = "Ajouter aux favoris"
    override val removeFavorite = "Retirer des favoris"
    override val rating = "Note"
    override fun rateAs(stars: Int) = if (stars <= 1) "Noter $stars étoile" else "Noter $stars étoiles"
    override val clearRating = "Effacer la note"

    override val newPlaylist = "Nouvelle playlist"
    override val playlistTitle = "Titre"
    override val description = "Description"
    override fun playlistKind(kind: PlaylistKind) = when (kind) {
        PlaylistKind.STATIC -> "Statique"
        PlaylistKind.DYNAMIC -> "Dynamique"
    }
    override fun playlistKindHint(kind: PlaylistKind) = when (kind) {
        PlaylistKind.STATIC -> "Vous choisissez les épisodes et leur ordre."
        PlaylistKind.DYNAMIC -> "Les épisodes suivent des règles, parmi vos abonnements."
    }
    override val keywords = "Mots-clés"
    override val keywordsHint = "Séparés par des virgules : dans le titre, la description ou le podcast"
    override val favoritesOnly = "Favoris seulement"
    override val minRating = "Note minimale"
    override val anyRating = "Toutes"
    override val unplayedOnly = "Non écoutés seulement"
    override fun playlistOrder(order: PlaylistOrder) = when (order) {
        PlaylistOrder.NEWEST -> "Les plus récents d'abord"
        PlaylistOrder.OLDEST -> "Les plus anciens d'abord"
        PlaylistOrder.RANDOM -> "Aléatoire"
    }
    override val sortOrder = "Ordre"
    override val episodeLimit = "Nombre d'épisodes au plus"
    override fun episodeCount(count: Int) = if (count <= 1) "$count épisode" else "$count épisodes"
    override val playAll = "Tout lire"
    override val shuffle = "Mélanger"
    override val editPlaylist = "Modifier"
    override val deletePlaylist = "Supprimer la playlist"
    override fun deletePlaylistConfirm(title: String) = "Supprimer « $title » ? Ses partages et son flux disparaissent aussi."
    override val addToPlaylist = "Ajouter à une playlist"
    override fun addedTo(title: String) = "Ajouté à « $title »."
    override val noPlaylistToAddTo = "Aucune playlist statique à compléter : créez-en une dans Playlists."
    override val removeFromPlaylist = "Retirer de la playlist"
    override val emptyPlaylists = "Aucune playlist pour l'instant."
    override val emptyPlaylist = "Cette playlist est vide."
    override val choosePlaylist = "Choisissez une playlist."
    override val sharedPlaylists = "Partagées avec moi"
    override fun playlistSharedBy(name: String) = "Partagée par $name"
    override val feed = "Flux RSS"
    override val feedHint = "Cette adresse secrète s'ouvre dans n'importe quelle appli de podcasts."
    override val publishFeed = "Publier le flux"
    override val newFeedAddress = "Nouvelle adresse"
    override val unpublishFeed = "Retirer le flux"
    override fun feedPublished(title: String) = "Flux de « $title » publié."

    override val nowPlaying = "En cours de lecture"
    override val pause = "Pause"
    override fun skipBack(seconds: Int) = "Reculer de $seconds s"
    override fun skipForward(seconds: Int) = "Avancer de $seconds s"
    override fun speed(value: Float) = (if (value % 1f == 0f) value.toInt().toString() else value.toString().replace('.', ',')) + "×"
    override val next = "Épisode suivant"
    override val close = "Fermer"
    override val emptyQueue = "La file d'attente est vide."
    override val addToQueue = "Ajouter à la file d'attente"
    override val removeFromQueue = "Retirer de la file d'attente"
    override val playNext = "Lire ensuite"
    override val moveUp = "Monter dans la file"
    override val clearQueue = "Vider la file d'attente"
    override val playbackFailed = "Impossible de lire cet épisode."

    override val profile = "Profil"
    override val displayName = "Nom affiché"
    override fun role(role: UserRole) = when (role) {
        UserRole.ADMIN -> "Administrateur"
        UserRole.USER -> "Utilisateur"
    }
    override val profileSaved = "Profil enregistré."
    override val changePassword = "Changer le mot de passe"
    override val currentPassword = "Mot de passe actuel"
    override val newPassword = "Nouveau mot de passe"
    override fun passwordHint(minimum: Int) = "Au moins $minimum caractères"
    override val passwordChanged = "Mot de passe changé : vos autres appareils devront se reconnecter."
    override val devices = "Appareils connectés"
    override val thisDevice = "Cet appareil"
    override val noDevices = "Aucune application ni aucun terminal n'est connecté à ce compte."
    override fun lastUsed(date: LocalDate) = "Utilisé le ${shortDate(date)}"
    override val neverUsed = "Jamais utilisé"
    override val revokeDevice = "Déconnecter"
    override fun revokeDeviceConfirm(name: String) = "Déconnecter « $name » ? Il devra se reconnecter."
    override val users = "Utilisateurs"
    override val you = "vous"
    override val addUser = "Ajouter un utilisateur"
    override fun userCreated(name: String) = "Compte de $name créé."
    override val makeAdmin = "Rendre administrateur"
    override val removeAdmin = "Retirer le rôle d'administrateur"
    override val resetPassword = "Réinitialiser le mot de passe"
    override fun resetPasswordOf(name: String) = "Nouveau mot de passe pour $name"
    override val resetPasswordNotice = "Tous ses appareils seront déconnectés."
    override fun passwordReset(name: String) = "Mot de passe de $name réinitialisé : ses appareils sont déconnectés."
    override val deleteUser = "Supprimer le compte"
    override fun deleteUserConfirm(name: String) =
        "Supprimer le compte de $name ? Ses abonnements, ses positions d'écoute et ses partages seront effacés."
    override val create = "Créer"
    override val delete = "Supprimer"
    override val moreActions = "Plus d'actions"

    override val sharedWithMe = "Partagés avec moi"
    override fun sharedBy(name: String) = "Partagé par $name"
    override fun recommendedBy(name: String) = "Recommandé par $name"
    override fun permission(permission: SharePermission) = when (permission) {
        SharePermission.READ -> "Lecture seule"
        SharePermission.WRITE -> "Lecture et modification"
    }
    override val sharePermission = "Droits"
    override val decline = "Retirer"
    override val share = "Partager"
    override fun shareTitle(title: String) = "Partager « $title »"
    override val recommend = "Recommander"
    override fun recommendTitle(title: String) = "Recommander « $title »"
    override val shareWith = "Avec qui ?"
    override val shareMessage = "Message (facultatif)"
    override val sharedWith = "Partagé avec"
    override val stopSharing = "Ne plus partager"
    override fun sharedWithUser(name: String) = "Partagé avec $name."
    override fun recommendedTo(name: String) = "Recommandé à $name."
    override val noOtherUsers = "Aucun autre compte sur ce serveur."
    override fun ownerNote(name: String) = "Note de $name"

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
    override val cliPlayDescription = "Lit un épisode dans le terminal : son numéro dans `list`, sinon celui à reprendre ou le premier de la file."
    override val cliQueueDescription = "Affiche la file d'attente."
    override val cliQueueAddDescription = "Ajoute un épisode (numéro dans `list`) à la file ; --next pour le lire ensuite."
    override val cliQueueRemoveDescription = "Retire un épisode de la file (numéro dans la file)."
    override val cliQueueClearDescription = "Vide la file d'attente."
    override val nothingToPlay = "Rien à lire."
    override val cliProfileDescription = "Affiche votre compte sur le serveur ; --name change le nom affiché."
    override val cliPasswdDescription = "Change votre mot de passe ; vos autres appareils devront se reconnecter."
    override val cliDevicesDescription = "Liste les appareils connectés au compte."
    override val cliDevicesRevokeDescription = "Déconnecte un appareil (numéro dans la liste)."
    override val cliUsersDescription = "Liste les comptes du serveur."
    override val cliUsersAddDescription = "Crée un compte (administrateurs) ; le mot de passe est demandé."
    override val cliUsersRemoveDescription = "Supprime un compte (administrateurs ; numéro dans la liste)."
    override val cliUsersPasswdDescription = "Réinitialise le mot de passe d'un compte (administrateurs)."
    override val cliUsersRoleDescription = "Rend un compte administrateur ou utilisateur (administrateurs)."
    override val cliSharedDescription = "Liste ce que les autres partagent avec vous."
    override val cliSharedRemoveDescription = "Retire un partage reçu (numéro dans la liste)."
    override val cliShareDescription = "Partage un abonnement (numéro dans `subscriptions`) ; sans destinataire, dit avec qui il l'est."
    override val cliWriteOption = "Autorise aussi la modification (note, téléchargement automatique)."
    override val cliUnshareDescription = "Ne partage plus un abonnement avec quelqu'un."
    override val cliRecommendDescription = "Recommande un épisode (numéro dans `list`) à quelqu'un."
    override val cliPlaylistsDescription = "Liste vos playlists, puis celles partagées avec vous."
    override val cliPlaylistShowDescription = "Affiche les épisodes d'une playlist (numéro dans `playlists`)."
    override val cliPlaylistCreateDescription = "Crée une playlist : statique, ou dynamique avec --dynamic ou des règles."
    override val cliPlaylistEditDescription = "Change le titre, la description ou les règles d'une playlist."
    override val cliDynamicOption = "Dynamique : les épisodes suivent des règles, parmi vos abonnements."
    override val cliPlaylistAddDescription = "Ajoute un épisode (numéro dans `list`) à une playlist statique."
    override val cliPlaylistRemoveDescription = "Retire un épisode d'une playlist statique (numéro dans la playlist)."
    override val cliPlaylistMoveDescription = "Déplace un épisode d'une playlist statique à la place donnée."
    override val cliPlaylistDeleteDescription = "Supprime une de vos playlists, avec ses partages et son flux."
    override val cliPlaylistPlayDescription = "Lit une playlist dans le terminal, avant la file d'attente."
    override val cliPlaylistShuffleDescription = "Mélange de nouveau une playlist en ordre aléatoire."
    override val cliPlaylistShareDescription = "Partage une playlist ; sans destinataire, dit avec qui elle l'est."
    override val cliPlaylistWriteOption = "Autorise aussi la modification (titre, épisodes, règles)."
    override val cliPlaylistUnshareDescription = "Ne partage plus une playlist avec quelqu'un."
    override val cliPlaylistFeedDescription = "Donne l'adresse du flux RSS d'une playlist, publié au besoin ; --renew la change, --off retire le flux."
    override val cliFavoriteDescription = "Ajoute un épisode (numéro dans `list`) aux favoris ; --off l'en retire."
    override val cliRateDescription = "Note un épisode (numéro dans `list`) de 1 à 5 étoiles ; 0 efface la note."
    override val rulesOnlyForDynamic = "Seule une playlist dynamique a des règles."
    override val onlyRandomShuffles = "Seule une playlist en ordre aléatoire se mélange."
    override val playlistNotShared = "Cette playlist n'est partagée avec personne."
    override fun playlistNoLongerSharedWith(name: String) = "Vous ne partagez plus cette playlist avec $name."
    override fun playlistDeleted(title: String) = "« $title » supprimée."
    override val feedUnpublished = "Flux retiré : son adresse ne marche plus."
    override fun favoriteAdded(title: String) = "« $title » ajouté aux favoris."
    override fun favoriteRemoved(title: String) = "« $title » retiré des favoris."
    override fun ratingCleared(title: String) = "Note de « $title » effacée."
    override val confirmPassword = "Confirmez le mot de passe"
    override val passwordsDiffer = "Les deux saisies diffèrent."
    override val confirmWithYes = "Relancez avec --yes pour confirmer."
    override val notShared = "Cet abonnement n'est partagé avec personne."
    override val nothingShared = "Rien n'est partagé avec vous."
    override fun noLongerSharedWith(name: String) = "Vous ne partagez plus cet abonnement avec $name."
    override val shareRemoved = "Partage retiré."
    override fun deviceSignedOut(name: String) = "« $name » est déconnecté."
    override fun userDeleted(name: String) = "Compte de $name supprimé."
    override val tuiHelp = "↑/↓ naviguer · entrée lire · a file d'attente · q quitter"
    override val tuiPlayerHelp = "espace lecture/pause · ←/→ −15 s/+30 s · +/− vitesse · n suivant"
}
