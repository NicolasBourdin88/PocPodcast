# ADR 0013 — Composants natifs : Material 3 Expressive, client SwiftUI, avatars

- Statut : accepté
- Date : 19 novembre 2026
- Complète : ADR 0001 (modules), ADR 0002 (textes partagés), ADR 0008 (lecture), ADR 0009 (comptes)

## Contexte

L'étape 10 (finition) demande d'utiliser au mieux les composants et les comportements natifs de chaque plateforme :
- sur Android, Material 3 Expressive, le langage visuel actuel d'Android ;
- sur iPhone et Mac, Liquid Glass, qui ne s'obtient qu'avec les composants natifs (onglets, navigation, barres d'outils). Compose dessine avec Skia et ne peut que l'imiter ;
- des champs de texte moins austères que les champs « outlined » ;
- des avatars à choisir, comme sur les plateformes de streaming, à condition de ne pas compromettre l'interopérabilité.

Il reste aussi deux points ouverts de l'étape 8 : l'OPML dans l'app iOS et l'envoi des positions hors ligne.

## Décision

### Compose : Material 3 Expressive

- **Versions** : Compose Multiplatform 1.13.0-rc01 et son material3 1.13.0-rc01, fondé sur Material3 1.5.0.
  - Les composants Expressive y sont stables, sauf l'indicateur de chargement et les formes (`MaterialShapes`).
  - La version stable 1.9.0 n'en avait qu'une partie. Une version candidate est acceptée : la stable doit suivre de peu.
  - Elle demande de compiler l'app Android pour l'API 37.1 ou plus (`compileSdk` 37.2).
- **Thème** : `MaterialExpressiveTheme` et son mouvement (`MotionScheme.expressive()`). Sur Android 12 et plus, les couleurs du fond d'écran (Material You), comme les apps du système ; ailleurs, les couleurs de l'app (`DesignTokens`).
- **Champs** : un seul champ de texte, rempli, arrondi et tonal (`AppTextField`), et une barre de recherche Material en pilule (`AppSearchField`).
  - Explorer n'a plus de bouton « Rechercher » : la touche du clavier lance la recherche.
  - L'ajout d'un flux RSS passe dans une boîte de dialogue.
- **Navigation** : la barre de navigation courte (`ShortNavigationBar`) sur téléphone, le rail large (`WideNavigationRail`) ailleurs ; le tiroir permanent reste sur les grandes fenêtres, pour la liste des abonnements.
- **Composants** :
  - l'indicateur de chargement Expressive et le tirer-pour-rafraîchir (Accueil, Bibliothèque) : l'indicateur sans pastille, le contenu descendant avec le geste pour lui laisser sa place ;
  - un bouton de lecture qui passe du rond au carré ; une ligne de progression plate dans le mini-lecteur (la vague d'Expressive dit qu'une chose charge, pas où en est l'écoute) ;
  - le lecteur dans une feuille modale (`ModalBottomSheet`) qui glisse depuis le bas, comme les lecteurs des plateformes, et se ferme en la tirant vers le bas, par le geste retour ou par son chevron ;
  - l'OPML dans le menu de débordement de la Bibliothèque.
- **Desktop** : une barre de menus native « Lecture » (lire ou mettre en pause, reculer, avancer, épisode suivant, vitesse), avec des raccourcis qui laissent l'édition de texte tranquille (⌘[ et ⌘], comme précédent et suivant dans un navigateur).

### iPhone, iPad et Mac : un client SwiftUI

- **Écrans en SwiftUI**, sur le même SDK Kotlin : le contrat (modèles, client d'API qui suit les liens, règles, textes français et anglais) reste celui de `:shared`. Aucun texte visible n'est écrit en Swift.
- **Module `:apple`** : il produit le framework `PodcastKit` (iOS et macOS) qui exporte `:shared` et `:player`.
  - SKIE rend le Kotlin naturel en Swift : fonctions `suspend` en `async`, flux en `AsyncSequence`, énumérations en `enum` Swift.
  - `PodcastSdk` est le point d'entrée côté Swift : comptes enregistrés, un client par compte, démo, lecteur, textes.
- **Erreurs** : les fonctions `suspend` du SDK déclarent `@Throws`. Sans cela, une exception Kotlin (réseau coupé) arrête l'app au lieu de devenir une erreur Swift.
- **macOS** : `:shared` et `:player` gagnent la cible macOS ; le code iOS devient du code Apple. AVPlayer (`AppleAudioPlayer`) sert aussi sur Mac ; seule la session audio reste propre à iOS.
- **Composants natifs** :
  - `TabView` avec l'onglet de recherche à part et le mini-lecteur au-dessus de la barre d'onglets (`tabViewBottomAccessory`), barre latérale sur iPad et Mac ;
  - `searchable`, listes avec actions de balayage et menus contextuels, `refreshable`, feuilles ;
  - boutons en verre (`glass`, `glassProminent`), graphique Swift Charts pour « Mon écoute » ;
  - sélecteurs de fichiers du système pour l'OPML, ce qui règle le point ouvert de l'étape 8 ;
  - menu « Lecture » sur Mac.
- **Couleurs** : comme les apps d'Apple, une seule couleur d'accent (`AccentColor` du catalogue d'assets), déclinée pour le clair, le sombre et le contraste élevé, comme les couleurs système (le bleu système vaut `#007AFF` en clair et `#0A84FF` en sombre). Elle teinte les commandes ; textes et fonds sont les couleurs sémantiques du système.
  - Le violet de la marque (`#6750A4`) ne tient en sombre que 2,6:1 sur le fond : l'accent sombre est `#9678E8`, le contraste élevé `#4D3486` et `#B497FF`.
  - Ces valeurs sont dans les jetons partagés (`DesignTokens.appleAccent`). Un test vérifie leur contraste (WCAG : 4,5:1 en texte, 7:1 en contraste élevé, 3:1 pour un symbole blanc sur un bouton plein) et que le catalogue les reprend.
- **Listes sur Mac** : style « plain » pour les listes de contenu. Le style par défaut (« inset ») garde chaque ligne à la hauteur d'une ligne de texte et coupait les épisodes.
- **Construction** : Xcode lance Gradle. Sur iOS, le framework de débogage habituel (`embedAndSignAppleFrameworkForXcode`).
  - Sur Mac, le framework de production (`linkReleaseFrameworkMacosArm64`), car les caches précompilés de Kotlin/Native 2.4.21 pour macOS demandent un SDK plus récent que celui de Xcode 26.3. Lié d'un bloc, il ne garde que ce que l'app utilise. Le premier lien prend une dizaine de minutes.
  - Les arguments par défaut de SKIE sont désactivés : ils ne se lient pas avec les bibliothèques en cache, et l'app Swift passe tous les arguments.
- **L'app Compose pour iOS reste** : elle montre que l'UI partagée tourne aussi sur iOS. L'app SwiftUI (`fr.podcast.apple`) est le client Apple recommandé.

### Avatars

- **Catalogue partagé** (`Avatar` dans `:shared`) : douze images, un emoji sur une couleur. Seul l'identifiant voyage.
- **Valeur par défaut** : avant tout choix, un avatar déduit de l'identifiant de l'utilisateur, le même sur toutes les plateformes.
- **Serveur** :
  - la migration `V9` ajoute `users.avatar` ;
  - le template `update-profile` accepte `avatar`, avec les choix en `options` HAL-FORMS ;
  - les utilisateurs et les partages le portent.
- **Interopérabilité** : rien ne change pour l'API gPodder ni pour l'Open Podcast API, qui n'ont pas d'avatar.
- **Clients** :
  - apps : une grille où un toucher enregistre ;
  - client léger : des tuiles qui enregistrent au clic ;
  - CLI : `podcast profile --avatar fox`.

### Client léger

- **Pochettes servies par le serveur** : `GET /podcasts/{id}/artwork` (lien `artwork` du podcast).
  - Le serveur lit une fois l'image du flux (`artworkUrl`), la coupe au carré, la réduit à 256 pixels et la garde en JPEG sous `MEDIA_DIR/artwork/`.
  - Le navigateur ne demande plus que notre serveur. Un bloqueur de contenu, ou la protection contre le hotlinking d'un hébergeur, cachait certaines pochettes (celle de Fragmented, sur `image.simplecastcdn.com`). Et une vignette de 56 pixels ne charge plus une image de 3000 pixels.
  - Sans image lisible : une vignette SVG, les initiales du podcast sur sa couleur, comme dans les apps. L'image est redemandée au plus une fois par heure.
  - Les résultats d'Explorer gardent l'adresse donnée par l'annuaire : les servir demanderait un proxy ouvert à n'importe quelle adresse.
  - Les apps Compose, qui n'affichaient que des initiales, prennent les mêmes pochettes par le SDK (`PodcastRepository.artwork`, qui suit le lien `artwork`), avec le jeton du compte. Une image de 256 pixels pèse peu sur un réseau mobile et en mémoire.
- **Grandes fenêtres** (1200 pixels et plus, comme la classe « Large » des apps) : plus de largeur maximale.
  - Les listes passent en colonnes d'au moins 440 pixels.
  - Sur l'Accueil, la file d'attente devient un panneau à droite, qui reste visible pendant le défilement.
  - Les sections des Réglages se placent côte à côte.
- **Initiales** : les mots sans lettre ni chiffre sont sautés (« Fragmented - AI Developer Podcast » donne « FA », et non « F- »).

### Positions hors ligne

`PlaybackController` garde la dernière position non envoyée de chaque épisode quand le serveur est injoignable. Il le dit une fois, puis l'envoie à la première synchronisation à laquelle le serveur répond, et avant que l'app se ferme.

## Conséquences

- Compose Multiplatform est en version candidate ; passer à la 1.13.0 stable dès sa sortie.
- Deux apps sur iPhone : l'app Compose (`fr.podcast.Podcast`) et l'app SwiftUI (`fr.podcast.apple`).
- Une modification du Kotlin relance sur Mac un lien d'une dizaine de minutes. Avec un Xcode plus récent, le framework de débogage suffirait.
- Les positions en attente vivent en mémoire : si l'app est fermée hors ligne, elles sont perdues. Le serveur prend toujours la dernière écriture, et l'API ne transmet pas l'heure de la mesure.
- L'app SwiftUI gère aussi les comptes du serveur pour un administrateur (créer, rendre administrateur, réinitialiser un mot de passe, supprimer) : il n'y a pas d'inscription libre (ADR 0009).
- L'app SwiftUI charge toujours l'image du flux elle-même (`ArtworkCache`) : aucun bloqueur de contenu ne la filtre.
- Les pochettes gardées ne sont jamais purgées : quelques dizaines de kilo-octets par podcast.
- Hors périmètre : tests d'interface automatisés de l'app SwiftUI, partages et édition des playlists dans l'app SwiftUI. Le client léger et les apps Compose les offrent.
