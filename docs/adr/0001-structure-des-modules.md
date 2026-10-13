# ADR 0001 — Structure des modules Gradle

- Statut : accepté
- Date : 13 octobre 2026

## Contexte

Le plan prévoyait quatre modules : `:shared`, `:composeApp` (Android + iOS + desktop), `:server` et `:cli`, plus le projet Xcode `iosApp/`.

À la création du projet (Kotlin 2.4.21, Compose Multiplatform 1.12.1, AGP 9.4.1, Gradle 9.8.1), deux contraintes de l'outillage sont apparues :

1. **AGP 9 n'accepte plus `com.android.application` dans un module Kotlin Multiplatform.** Un module KMP qui cible Android doit utiliser `com.android.kotlin.multiplatform.library`, et l'APK doit être produit par un module Android séparé. C'est la structure que JetBrains recommande désormais.
2. **Compose 1.12 exige `compileSdk` 37.**

## Décision

- `:composeApp` devient une **bibliothèque** KMP (Android, iOS, desktop). Il garde la cible desktop et sa tâche `run`, si bien que `./gradlew :composeApp:run` fonctionne toujours.
- Un module **`:androidApp`** (`com.android.application`) ne contient que `MainActivity` et le manifeste. Il dépend de `:composeApp`.
- `:shared` utilise aussi `com.android.kotlin.multiplatform.library`.
- `compileSdk = 37`, `targetSdk = 36`, `minSdk = 26`.
- `iosApp/` vient du template officiel [KMP-App-Template](https://github.com/Kotlin/KMP-App-Template), branché sur le framework `ComposeApp`. Seules les cibles Apple Silicon (`iosArm64`, `iosSimulatorArm64`) sont générées.
- Package racine : `fr.podcast`. Le projet n'a pas encore de nom définitif ; le renommage se fera dans un ADR dédié.

## Conséquences

- La commande P2 du plan devient `./gradlew :androidApp:installDebug`.
- Le desktop pourra plus tard sortir dans un `:desktopApp`, sur le même modèle, si `:composeApp` grossit.
- La TUI Mosaic a besoin d'un vrai terminal : on la lance via `:cli:installDist`, pas via `:cli:run`.
