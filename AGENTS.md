# AGENTS.md

Consignes pour les agents de code (Claude Code, Codex, Cursor…) qui travaillent sur ce dépôt. Le [README](README.md) présente le projet ; ce fichier dit comment y travailler sans rien casser. Claude Code le lit par `CLAUDE.md`.

## Le projet

Projet de master, d'octobre à mars : un système de gestion et d'écoute de podcasts inspiré de PinePods. Un service multi-utilisateurs et plusieurs clients, compatibles entre eux et avec des clients tiers (AntennaPod, Kasts, gPodder).

- **Fonctions imposées** : abonnements (URL, recherche Podcast Index, OPML, annotation, mise à jour automatique, téléchargement avec purge), lecture (playlists statiques et dynamiques, partage, synchronisation, statistiques, notes et favoris), recherche multicritère, multi-utilisateurs avec partages et droits, fichiers mutualisés mais état d'écoute propre à chacun.
- **Architecture imposée** : client léger, client mobile multiserveur, client riche, client TUI/CLI, un service, une API hypermédia.
- **État** : les dix étapes de [plan.md](plan.md) sont faites, chacune avec sa « définition de terminé ». Il reste la démo finale ([docs/demo.md](docs/demo.md)). Le travail actuel est de la finition : retours sur les apps, documentation.

## Carte du dépôt

| Chemin | Contenu |
|---|---|
| `shared/` | `:shared`, Kotlin Multiplatform (JVM, Android, iOS, macOS) : modèles, SDK (`RemotePodcastRepository`, comptes), `Strings` FR/EN, `DesignTokens`, `Destination` |
| `player/` | `:player` : `AudioPlayer` (Media3, AVPlayer, JavaFX Media) et `PlaybackController` partagé |
| `composeApp/` | `:composeApp`, bibliothèque KMP : UI Compose Multiplatform (Android, iOS, desktop) |
| `androidApp/` | `:androidApp` : l'APK (AGP 9 interdit `com.android.application` dans un module KMP, ADR 0001) |
| `iosApp/` | Projet Xcode qui héberge l'UI Compose sur iOS |
| `apple/`, `appleApp/` | `:apple` produit le framework `PodcastKit` (SKIE) ; `appleApp/` est l'app SwiftUI (iPhone, iPad, Mac) |
| `server/` | `:server`, Ktor : API hypermédia, client web (HTML + htmx), API gPodder et Open Podcast API ; source set `dev` (données de démo) |
| `cli/` | `:cli` : commandes Clikt et TUI Mosaic |
| `docs/` | `adr/` (décisions), `api/` (spécification, conventions, modèle de données), `guide.md`, `demo.md`, `etudes/`, `images/` |
| `poc/` | Preuves de concept des études |
| `tools/readme/` | Schéma d'architecture et planches de captures du README |

## Architecture

- **Kotlin partout.** Kotlin Multiplatform partage le *contrat* (modèles, client d'API, règles, textes) entre le serveur et les clients, pas seulement l'UI. Toute modification de modèle passe par `:shared`.
- **Une API hypermédia** (ADR 0003, 0006) : HAL et HAL-FORMS en JSON pour les apps, HTML pour le client léger, choisis par `Accept`. Les actions sont des `_templates` nommés décrits par un seul modèle `Affordance`, aussi rendu en formulaires htmx. Les clients ne construisent aucune URL : ils suivent les liens de la racine.
- **Persistance** (ADR 0004) : PostgreSQL, Flyway et SQLDelight (schéma dérivé des migrations `.sqm`) ; PostgreSQL embarqué (zonky) en développement et dans les tests.
- **Authentification** (ADR 0005, 0009) : Argon2id ; cookie de session pour le client léger ; jetons opaques par appareil (Bearer) pour les apps ; Basic et mots de passe d'application pour gPodder. Une seule règle `can()` : propriétaire et partages `READ`/`WRITE` ; 404 pour ce qui n'est pas lisible, 403 pour ce qui est lisible mais interdit. Pas d'inscription libre : un administrateur crée les comptes.
- **Client léger** : HTML rendu par le serveur et htmx (pas Compose Wasm : Compose Web est en bêta, et le CORS empêche de lire les flux depuis le navigateur).
- **Apps Compose** : Material 3 Expressive (ADR 0013), responsive selon la taille de la fenêtre, pas selon la plateforme (tableau ci-dessous).
- **App SwiftUI** (ADR 0013) : Liquid Glass ne s'obtient qu'avec les composants natifs ; l'app est écrite en SwiftUI sur `PodcastKit`, qui exporte `:shared` et `:player` (SKIE pour `suspend` et `Flow`). C'est le client Apple recommandé ; l'app Compose iOS reste pour montrer l'UI partagée.
- **Lecture** (ADR 0008) : `AudioPlayer` injecté par chaque point d'entrée ; position envoyée toutes les 15 s et à la pause ; file d'attente sur le serveur ; liens audio signés (24 h), car les lecteurs n'envoient pas de jeton.
- **Interopérabilité** (ADR 0011) : API gPodder dans le sous-ensemble qu'utilise AntennaPod, Open Podcast API pour les abonnements, sur le même journal `subscription_changes` ; flux RSS par playlist ; OPML. Ces API ne sont pas hypermédia : leurs formats sont imposés par les clients existants.

| Classe | Condition | Navigation | Contenu |
|---|---|---|---|
| Compact | largeur < 600 dp | barre du bas et mini-lecteur | une colonne |
| Hauteur compacte | hauteur < 480 dp | rail à gauche | deux colonnes, mini-lecteur sur une ligne |
| Moyen | 600 à 839 dp | rail | une colonne large |
| Étendu | 840 à 1199 dp | rail | liste et détail, file d'attente |
| Large | 1200 dp et plus | tiroir permanent avec les abonnements | liste, panneau latéral, lecteur en bas |

## Commandes

| Pour | Commande |
|---|---|
| Serveur de développement | `./gradlew :server:run` (port 8080, données et comptes de `DevSeed.kt`) |
| Second serveur | `PORT=8081 SERVER_NAME="Serveur B" ./gradlew :server:run` |
| Tous les tests | `./gradlew :shared:allTests :player:jvmTest :composeApp:desktopTest :server:test :cli:test` |
| Android | `./gradlew :androidApp:assembleDebug`, puis `adb -s <appareil> install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk` |
| Desktop | `./gradlew :composeApp:run` |
| SwiftUI | `xcodebuild -project appleApp/Podcast.xcodeproj -scheme Podcast -derivedDataPath build/apple-derived -destination 'platform=macOS,arch=arm64'` (ou `-destination 'id=<simulateur>'`) |
| CLI | `./gradlew :cli:installDist`, puis `cli/build/install/podcast/bin/podcast` |

`installDebug` installe sur tous les appareils branchés, téléphone personnel compris : préférer `assembleDebug` puis `adb -s`.

## Règles

- **Spécification d'abord.** Toute ressource, tout lien ou template nouveau passe par `server/src/main/resources/openapi/podcast-api.yaml` ; `ContractTest` échoue si la spécification et les routes divergent, ou si une réponse ne respecte pas son schéma.
- **`:shared` ne dépend d'aucune plateforme** : le code spécifique passe par `expect`/`actual` ou par des interfaces injectées.
- **Aucun texte visible en dur dans un client** : un nouveau texte est un membre de `Strings`, en français et en anglais (ADR 0002). Couleurs et palette des pochettes : `DesignTokens`.
- **Code en anglais, documentation en français.** Les décisions importantes vont dans `docs/adr/` (un fichier numéroté par décision).
- **Commits** : petits, logiques, au format Conventional Commits en anglais (`feat(server): …`, `fix(app): …`, `docs: …`) ; chaque commit compile. Dépôt local, sans remote : ne rien pousser.
- **Dépendances** : les versions les plus récentes, vérifiées plutôt que supposées (l'API de Mosaic a changé plusieurs fois).
- Package racine `fr.podcast`.

### Client léger

- Un élément qui se recharge seul (`reloadOn`, `#home`, `#queue`) porte `hx-disinherit="*"`, sinon ses formulaires et ses liens boostés héritent de son `hx-swap`/`hx-select` et leur réponse le vide (`WebTest.selfReloadingElementsPassNothingOn`).
- Un refus envoyé à htmx garde son statut et porte la phrase traduite des apps (`respondProblem`). Un succès répond par une phrase (`respondNotice`), par `HX-Trigger` (`respondChanged`), ou change de page par `HX-Location` (`respondLocation`), ce qui garde le lecteur.
- Une modification authentifiée par session exige l'en-tête `HX-Request` (protection CSRF).
- Une pochette passe par `artwork()` (`/podcasts/{id}/artwork`, gardée et réduite par le serveur), jamais par l'adresse du flux, que des bloqueurs de contenu cachent.

### Compose

- Un champ de texte est `AppTextField` (rempli, arrondi, tonal), une recherche `AppSearchField`, une attente `Loading` : pas de champ « outlined » ni de `CircularProgressIndicator`.
- Un bouton à bascule (`*IconToggleButtonColors`) nomme les couleurs de ses deux états : une couleur cochée omise prend celle du texte ambiant, illisible sur le fond plein.
- Les pochettes viennent du serveur par `Artwork` et `ArtworkImages` ; sans image, les initiales.

### SwiftUI

- Couleurs : la couleur d'accent du catalogue (`.tint`, `Color.accentColor`) et les couleurs sémantiques du système, jamais une couleur fixe.
- Une liste de contenu prend `.contentListStyle()` ; une requête annulée (`AppModel.isCancellation`) ne s'affiche pas ; sur Mac, chaque écran porte la barre du lecteur (`.playerBar()`), pages ouvertes comprises.
- Tout texte vient de `PodcastSdk.strings`. Les fonctions `suspend` du SDK déclarent `@Throws`, sinon une exception Kotlin arrête l'app Swift.

### SQLDelight

- Une requête avec `RETURNING` n'est exécutée qu'avec `executeAsOne()`.
- Un paramètre nullable comparé par `=` devient `IS ?`, que PostgreSQL refuse : utiliser un indicateur booléen.

## Vérifier un changement

- **UI Compose** : `./gradlew :composeApp:desktopTest` dessine chaque écran, à chaque taille de fenêtre et dans chaque langue, dans `composeApp/build/snapshots/`. Les regarder après toute modification d'UI.
- **Client léger** : `http://localhost:8080/dev/login/alice` (ou `bob`, `?next=/playlists`) connecte le navigateur sans mot de passe, depuis la machine seulement (source set `dev`). `WebTest` ne voit pas le comportement de htmx : cliquer dans les pages.
- **App desktop avec de vraies données** : `PODCAST_TAB=library PODCAST_WINDOW=1440x900 PODCAST_SNAPSHOT=/tmp/desktop.png ./gradlew :composeApp:run` ouvre un onglet, dimensionne la fenêtre et enregistre son contenu une fois chargé.
- **App SwiftUI (debug)** : arguments de lancement `-tab library`, `-appearance dark`, `-openPodcast YES`, `-player YES`, `-click "x,y;x,y"` (points depuis le coin haut gauche de la fenêtre), `-keys tab,down,return`, `-scrollBottom YES`, `-snapshot <fichier.png>` (l'app Mac enregistre ses propres fenêtres, sans permission d'enregistrement d'écran) et `-snapshotDelay <s>`. Un clic simulé qui marche peut encore échouer à la main : sans permission d'accessibilité, le bouton de la souris n'est pas vraiment enfoncé.
- **Simulateur et émulateur** : `xcrun simctl io booted screenshot <fichier.png>`, `adb exec-out screencap -p > <fichier.png>`.
- **Connecter une app sans le mot de passe de démo** : créer un mot de passe d'application (`podcast devices add "<nom>"`), l'écrire dans les comptes enregistrés de l'app (UserDefaults sur Apple, SharedPreferences sur Android, Preferences Java sur desktop), et ne jamais l'afficher.

## Pièges connus

- Ne pas compiler `:shared` ni `:server` pendant qu'un `./gradlew :server:run` tourne : le serveur charge ses classes à la demande et tombe en `NoClassDefFoundError`. Le relancer après.
- Un PostgreSQL embarqué peut rester orphelin après l'arrêt du serveur et bloquer le suivant : arrêter le processus `dev-postgres-<port>` par `kill -INT`.
- Un serveur cassé par une recompilation marque des flux en échec ; `POST /podcasts/{id}/refresh` (ou un démarrage avec `REFRESH_INTERVAL_MINUTES=1`) les relit.
- Empaqueter l'app desktop (`createDistributable`) demande un JDK qui sait le faire ; changer de JDK recompile `:shared`, donc relancer les serveurs ensuite.
- App SwiftUI sur Mac : Xcode lie le framework Kotlin de production (une dizaine de minutes), car les caches de Kotlin/Native demandent un SDK plus récent que celui de Xcode 26.3. Les arguments par défaut de SKIE restent désactivés (ils ne se lient pas avec les bibliothèques en cache).
- Les mots de passe de démo sont dans `server/src/dev/kotlin/fr/podcast/server/dev/DevSeed.kt` : ne jamais les recopier dans une sortie ou un message. Un mot de passe d'application n'est montré qu'une fois.

## Documentation à tenir à jour

- `README.md` : vue d'ensemble, tableau des fonctionnalités par client, captures. Les images se régénèrent avec `tools/readme/` (voir son README).
- `docs/guide.md` : l'utilisation de chaque client, pas à pas.
- `docs/api/` et la spécification OpenAPI pour l'API ; `docs/adr/` pour les décisions.
