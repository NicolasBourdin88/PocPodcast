# Podcast

Système de gestion et d'écoute de podcasts (projet de master). Il se compose d'un service multi-utilisateurs et de plusieurs clients : mobile, desktop, web léger, CLI/TUI. Tous partagent le même module Kotlin Multiplatform.

- Contexte et décisions : [CLAUDE.md](CLAUDE.md)
- Feuille de route : [plan.md](plan.md)
- Décisions d'architecture : [docs/adr/](docs/adr/)
- Contrat de l'API (spécification OpenAPI, conventions, modèle de données) : [docs/api/](docs/api/README.md)

## Modules

| Module | Cibles | Rôle |
|---|---|---|
| `:shared` | JVM, Android, iOS | Modèles, textes traduits, SDK client (comptes, `RemotePodcastRepository` : Accueil, abonnements, recherche, OPML) |
| `:player` | JVM, Android, iOS | Lecture audio : interface `AudioPlayer`, contrôleur partagé, Media3, AVPlayer et JavaFX Media ([ADR 0008](docs/adr/0008-lecture-audio.md)) |
| `:composeApp` | Android, iOS, Desktop (JVM) | UI Compose Multiplatform, responsive par taille de fenêtre |
| `:androidApp` | Android | Point d'entrée Android (`MainActivity`) |
| `iosApp/` | iOS | Projet Xcode qui héberge l'UI Compose |
| `:server` | JVM | Ktor + PostgreSQL : API hypermédia (HAL / HAL-FORMS) et client léger HTML/htmx |
| `:cli` | JVM | Commandes Clikt et TUI Mosaic |

## Langues

L'interface existe en français et en anglais. Les textes, les couleurs et la navigation sont définis une seule fois dans `:shared` (voir l'[ADR 0002](docs/adr/0002-chaines-et-ressources-partagees.md)). Chaque client choisit sa langue ainsi :

- Compose : langue du système, ou langue de l'app dans les réglages Android 13 et plus ;
- client léger : en-tête `Accept-Language` du navigateur ;
- CLI : `podcast --lang en list` (par défaut, la langue du système).

## Prérequis

- JDK 17 ou plus récent (21 recommandé)
- Android SDK (Android Studio), avec `sdk.dir` dans `local.properties`
- Xcode 26 sur un Mac Apple Silicon pour iOS
- Ni Docker ni PostgreSQL ne sont nécessaires en développement : le serveur et les tests utilisent un PostgreSQL embarqué.

## Lancer le serveur

```bash
./gradlew :server:run
```

- http://localhost:8080 : client léger (page de connexion, puis Accueil, Explorer, Bibliothèque et Réglages)
- http://localhost:8080/docs : documentation de l'API (Swagger UI)

Au premier lancement, un PostgreSQL embarqué est créé dans `server/build/dev-postgres-8080`, avec des données de démo lues dans de **vrais flux** (Les Cast Codeurs, Talking Kotlin, Fragmented, Podnews Weekly Review, qui a des chapitres et des transcriptions). Sans réseau, les podcasts sont créés vides et lus plus tard. Les **comptes de démo** sont décrits dans [DevSeed.kt](server/src/dev/kotlin/fr/podcast/server/dev/DevSeed.kt) : Alice est abonnée aux quatre podcasts, Bob à deux, chacun avec sa propre progression. Les fichiers téléchargés vont dans `server/build/dev-media-8080`.

Pour repartir de zéro, on arrête le serveur et on supprime ces deux dossiers.

Pour essayer le multiserveur, on lance un second serveur, avec sa propre base, dans un autre terminal :

```bash
PORT=8081 SERVER_NAME="Serveur B" ./gradlew :server:run
```

En production, le point d'entrée `fr.podcast.server.ApplicationKt` exige une vraie base (`DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`) et active les cookies `Secure`.

Variables d'environnement (voir les ADR [0007](docs/adr/0007-flux-telechargements-et-purge.md) et [0009](docs/adr/0009-comptes-et-partages.md)) :

| Variable | Défaut | Rôle |
|---|---|---|
| `PORT`, `SERVER_NAME` | `8080`, `Podcast` | Port d'écoute et nom affiché aux clients |
| `ADMIN_USERNAME`, `ADMIN_PASSWORD` | aucune | Crée le premier administrateur au démarrage, seulement si la base n'a aucun compte (8 caractères au moins pour le mot de passe) |
| `PODCASTINDEX_KEY`, `PODCASTINDEX_SECRET` | aucune | Ajoute Podcast Index à la recherche (clés gratuites sur api.podcastindex.org) ; sans elles, seul Apple Podcasts est interrogé |
| `REFRESH_INTERVAL_MINUTES` | `60` | Délai avant de relire un flux |
| `MEDIA_DIR` | `media` | Dossier des fichiers audio téléchargés |
| `MEDIA_RETENTION_DAYS` | `30` | Âge à partir duquel un fichier est purgé (sauf épisode gardé ou en cours d'écoute) |
| `MEDIA_MAX_GB` | aucun | Quota disque : au-delà, les fichiers les plus anciens sont purgés |
| `AUTO_DOWNLOAD_LATEST` | `1` | Nombre d'épisodes récents téléchargés pour un abonnement en téléchargement automatique |
| `ALLOW_PRIVATE_FEEDS` | `false` | Autorise les flux sur un réseau privé (développement seulement : protection SSRF) |

## Lancer les clients

Au premier lancement, chaque app demande l'adresse du serveur et un compte. On peut aussi choisir les données de démo. Les autres comptes s'ajoutent dans **Réglages**, éventuellement sur d'autres serveurs, et l'on passe de l'un à l'autre d'un toucher.

Android (émulateur ou appareil branché) ; depuis l'émulateur, le serveur est à `http://10.0.2.2:8080` :

```bash
./gradlew :androidApp:installDebug
```

Desktop :

```bash
./gradlew :composeApp:run
```

Application macOS empaquetée (`composeApp/build/compose/binaries/main/app/Podcast.app`) :

```bash
./gradlew :composeApp:createDistributable
```

iOS : ouvrir `iosApp/iosApp.xcodeproj` dans Xcode et lancer sur un simulateur. Le serveur y est à `http://localhost:8080`.

CLI : se connecter, puis afficher l'Accueil :

```bash
./gradlew :cli:installDist
```

```bash
cli/build/install/podcast/bin/podcast login
```

```bash
cli/build/install/podcast/bin/podcast list
```

Les autres commandes sont `accounts`, `use 2`, `logout` et `tui` (la TUI a besoin d'un vrai terminal, pas de `./gradlew :cli:run`). L'option `--demo` utilise les données de démo sans serveur.

Abonnements : les listes sont numérotées, et les commandes suivantes reprennent ces numéros.

```bash
cli/build/install/podcast/bin/podcast search kotlin
```

```bash
cli/build/install/podcast/bin/podcast subscribe 2
```

Ensuite : `subscriptions`, `note 3 À écouter dans le train` (sans texte, la note est effacée), `unsubscribe 3`, `opml export podcasts.opml` et `opml import podcasts.opml`. `subscribe` accepte aussi l'adresse d'un flux.

## Écouter

La position d'écoute suit l'utilisateur d'un client à l'autre, ainsi que la file d'attente : un épisode commencé sur le téléphone reprend au même endroit sur l'ordinateur ([ADR 0008](docs/adr/0008-lecture-audio.md)).

- **Apps** :
  - ▶ sur un épisode (Accueil, page d'un podcast) le lance, et ≡+ l'ajoute à la file d'attente ;
  - le mini-lecteur, ou la barre de lecture sur grand écran, ouvre le lecteur plein écran ;
  - Android et iOS continuent en arrière-plan (notification, écran verrouillé, casque) ;
  - en mode démo, la lecture est simulée, sans son.
- **Client léger** : ▶ sur l'Accueil ; le lecteur reste en bas de page quand on change de page.
- **CLI** : `play` reprend l'épisode en cours, ou le premier de la file ; avec un numéro de `list`, il lance cet épisode.

```bash
cli/build/install/podcast/bin/podcast play 3
```

Dans la TUI (`play` ou `tui`) :
- `entrée` lit l'épisode sélectionné ;
- `espace` met en pause ou reprend ;
- `←` / `→` reculent de 15 s ou avancent de 30 s ;
- `+` / `-` changent la vitesse ;
- `n` passe à l'épisode suivant de la file, `a` y ajoute l'épisode sélectionné ;
- `q` quitte, après avoir envoyé la position.

File d'attente : `queue`, `queue add 4` (`--next` pour le lire ensuite), `queue remove 1`, `queue clear`.

## Comptes et partages

Un administrateur crée les comptes ; chacun change ensuite son nom et son mot de passe, et peut déconnecter un appareil perdu ([ADR 0009](docs/adr/0009-comptes-et-partages.md)). Dans les données de démo, Alice est administratrice.

- **Partager un abonnement** : en lecture seule, ou en lecture et modification (note, téléchargement automatique). Seul le propriétaire se désabonne ou le partage à nouveau.
- **Recommander un épisode** : à un autre compte, avec un message.
- **Partagés avec moi** : ce que les autres partagent apparaît à part, dans la Bibliothèque. On peut s'abonner soi-même au podcast partagé, ou retirer le partage.
- **Où** :
  - apps : Réglages (profil, mot de passe, appareils, comptes du serveur pour un administrateur), Bibliothèque et page d'un podcast ;
  - client léger : Réglages, Bibliothèque, et « Recommander » sous les nouveaux épisodes de l'Accueil ;
  - CLI : les commandes ci-dessous.

```bash
cli/build/install/podcast/bin/podcast share 2 bob --write --message "Pour le train"
```

Ensuite : `share 2` (avec qui il est partagé), `unshare 2 bob`, `shared` puis `shared remove 1`, `recommend 3 alice`, `profile --name …`, `passwd`, `devices` puis `devices revoke 2`, et pour un administrateur `users`, `users add carol --name Carol`, `users role 3 admin`, `users passwd 3`, `users remove 3 --yes`.

## Tests

```bash
./gradlew :shared:allTests :player:jvmTest :composeApp:desktopTest :server:test :cli:test
```

- `:server:test` démarre un PostgreSQL embarqué et contient trois garde-fous :
  - `ContractTest` vérifie que la spécification OpenAPI et les routes correspondent, et que chaque réponse respecte son schéma ;
  - `SdkTest` fait tourner le SDK de `:shared` contre le vrai serveur ;
  - `ApiTest` et `WebTest` couvrent l'API et le client léger.
- `:player:jvmTest` vérifie le contrôleur de lecture avec un lecteur factice : envoi de la position, épisode suivant de la file, nouvel essai après une erreur.
- `:composeApp:desktopTest` rend les écrans à chaque taille de fenêtre et dans chaque langue, dans `composeApp/build/snapshots/`.
