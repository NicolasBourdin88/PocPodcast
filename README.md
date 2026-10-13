# Podcast

Système de gestion et d'écoute de podcasts (projet de master). Il se compose d'un service multi-utilisateurs et de plusieurs clients : mobile, desktop, web léger, CLI/TUI. Tous partagent le même module Kotlin Multiplatform.

- Contexte et décisions : [CLAUDE.md](CLAUDE.md)
- Feuille de route : [plan.md](plan.md)
- Décisions d'architecture : [docs/adr/](docs/adr/)
- Contrat de l'API (spécification OpenAPI, conventions, modèle de données) : [docs/api/](docs/api/README.md)

## Modules

| Module | Cibles | Rôle |
|---|---|---|
| `:shared` | JVM, Android, iOS | Modèles, textes traduits, SDK client (comptes, `RemotePodcastRepository`) |
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

- http://localhost:8080 : client léger (page de connexion, puis Accueil)
- http://localhost:8080/docs : documentation de l'API (Swagger UI)

Au premier lancement, un PostgreSQL embarqué est créé dans `server/build/dev-postgres-8080`, avec des données de démo. Les **comptes de démo** sont décrits dans [DevSeed.kt](server/src/dev/kotlin/fr/podcast/server/dev/DevSeed.kt) : Alice est abonnée aux quatre podcasts, Bob à deux, chacun avec sa propre progression.

Pour essayer le multiserveur, on lance un second serveur, avec sa propre base, dans un autre terminal :

```bash
PORT=8081 SERVER_NAME="Serveur B" ./gradlew :server:run
```

En production, le point d'entrée `fr.podcast.server.ApplicationKt` exige une vraie base (`DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`) et active les cookies `Secure`.

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

## Tests

```bash
./gradlew :shared:allTests :composeApp:desktopTest :server:test :cli:test
```

- `:server:test` démarre un PostgreSQL embarqué et contient trois garde-fous :
  - `ContractTest` vérifie que la spécification OpenAPI et les routes correspondent, et que chaque réponse respecte son schéma ;
  - `SdkTest` fait tourner le SDK de `:shared` contre le vrai serveur ;
  - `ApiTest` et `WebTest` couvrent l'API et le client léger.
- `:composeApp:desktopTest` rend les écrans à chaque taille de fenêtre et dans chaque langue, dans `composeApp/build/snapshots/`.
