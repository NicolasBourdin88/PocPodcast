# Podcast

Système de gestion et d'écoute de podcasts (projet de master) : un service multi-utilisateurs et plusieurs clients — mobile, desktop, web léger, CLI/TUI — qui partagent le même module Kotlin Multiplatform.

- Contexte et décisions : [CLAUDE.md](CLAUDE.md)
- Feuille de route : [plan.md](plan.md)
- Décisions d'architecture : [docs/adr/](docs/adr/)

## Modules

| Module | Cibles | Rôle |
|---|---|---|
| `:shared` | JVM, Android, iOS | Modèles, `PodcastRepository`, `LoadHomeState` |
| `:composeApp` | Android, iOS, Desktop (JVM) | UI Compose Multiplatform, responsive par taille de fenêtre |
| `:androidApp` | Android | Point d'entrée Android (`MainActivity`) |
| `iosApp/` | iOS | Projet Xcode qui héberge l'UI Compose |
| `:server` | JVM | Ktor : API hypermédia (HAL) + client léger HTML/htmx |
| `:cli` | JVM | `podcast list` (Clikt) et `podcast tui` (Mosaic) |

Pour l'instant toutes les données viennent de `FakePodcastRepository` (étape 1 du plan).

## Langues

L'interface existe en français et en anglais. Les textes, les couleurs et la navigation sont définis une seule fois dans `:shared` (voir l'[ADR 0002](docs/adr/0002-chaines-et-ressources-partagees.md)). Chaque client choisit sa langue ainsi :

- Compose : langue du système, ou langue de l'app dans les réglages Android 13 et plus ;
- client léger : en-tête `Accept-Language` du navigateur ;
- CLI : `podcast --lang en list` (par défaut, la langue du système).

## Prérequis

- JDK 17 ou plus récent (21 recommandé)
- Android SDK (Android Studio), avec `sdk.dir` dans `local.properties`
- Xcode 26 sur un Mac Apple Silicon pour iOS

## Lancer chaque cible

Tests du module partagé (JVM + simulateur iOS) :

```bash
./gradlew :shared:allTests
```

Android (émulateur ou appareil branché) :

```bash
./gradlew :androidApp:installDebug
```

Desktop :

```bash
./gradlew :composeApp:run
```

iOS : ouvrir `iosApp/iosApp.xcodeproj` dans Xcode et lancer sur un simulateur. Le build Xcode appelle Gradle tout seul.

Service et client léger, sur http://localhost:8080 :

```bash
./gradlew :server:run
```

La même page en HAL :

```bash
curl -H "Accept: application/hal+json" localhost:8080/
```

CLI :

```bash
./gradlew :cli:run --args="list"
```

La TUI a besoin d'un vrai terminal, que Gradle ne fournit pas. Il faut l'installer puis la lancer directement :

```bash
./gradlew :cli:installDist
```

```bash
cli/build/install/podcast/bin/podcast tui
```

## Tests

```bash
./gradlew :shared:allTests :composeApp:desktopTest :server:test :cli:test
```

`:composeApp:desktopTest` rend l'Accueil aux cinq tailles de fenêtre dans `composeApp/build/snapshots/` pour vérifier le responsive sans lancer l'application.
