# CLAUDE.md — contexte du projet

Fichier lu automatiquement par Claude Code. Il résume les décisions prises lors de la phase de cadrage (conversation claude.ai d'octobre 2026). Le plan par étapes est dans `plan.md`.

## Le projet

Projet de master en groupe mixte apprentis / non-apprentis, octobre → mars : un **système de gestion et d'écoute de podcasts** inspiré de PinePods. Un service multi-utilisateurs + plusieurs clients compatibles entre eux et avec des clients tiers (AntennaPod, Kasts, gPodder).

Fonctionnalités imposées par le sujet : abonnements (URL, recherche Podcast Index, OPML import/export, annotation, mise à jour auto, téléchargement local avec purge auto), lecture (playlists statiques et dynamiques — mots-clés, favoris/notes, aléatoire —, partage, synchro de l'état, stats, notation/favoris), fiches podcast/épisode avec recherche multicritère, multi-utilisateurs avec partage et droits (épisodes, playlists, abonnements), fichiers mutualisés mais état de lecture par utilisateur.

Architecture imposée : client léger, client mobile **multiserveur**, client riche, client TUI/CLI, service, **API hypermédia**.

## Décisions d'architecture

- **Kotlin partout.** La valeur de KMP ici est de partager le *contrat* (modèles, client d'API, règles) entre serveur et clients, pas seulement l'UI.
- `:shared` (KMP : JVM, Android, iOS) : modèles, `PodcastRepository`, états d'écran (`LoadHomeState`). Toute modification de modèle passe par ce module.
- **Chaînes et ressources communes dans `:shared`** (ADR 0002) : catalogue `Strings` français/anglais, `DesignTokens` (couleurs, palette des pochettes), `Destination` (navigation). Aucun texte visible en dur dans les clients ; un nouveau texte = un membre de `Strings`.
- `:server` : **Ktor**. Une seule API hypermédia, deux représentations selon `Accept` : **HAL JSON** pour les clients riches, **HTML** pour le client léger. Les actions sont décrites en **HAL-FORMS** (`_templates` nommés) à partir d'un seul modèle `Affordance`, aussi rendu en formulaires htmx (ADR 0003).
- Persistance : **PostgreSQL + Flyway + SQLDelight** (dialecte PostgreSQL, schéma dérivé des migrations `.sqm`) ; SQLDelight/SQLite côté clients pour le hors-ligne ; PostgreSQL embarqué (zonky) dans les tests (ADR 0004).
- Authentification : Argon2id ; cookie de session pour le client léger ; **jetons opaques par appareil** (Bearer) pour les clients riches et le multiserveur ; Basic + mots de passe d'application pour gPodder ; droits = propriétaire + partages `READ`/`WRITE` via une seule règle `can()`, 404 pour ce qui n'est pas lisible, 403 pour ce qui est lisible mais interdit (ADR 0005). Comptes gérés par les administrateurs, partages d'abonnements et recommandations d'épisodes : ADR 0009.
- **Client léger = HTML rendu côté serveur + htmx**, pas Compose Wasm (Compose Web est en bêta, et le CORS empêche de lire les flux RSS depuis le navigateur).
- `:composeApp` : **Compose Multiplatform** pour mobile (Android, iOS) et client riche (desktop JVM, y compris macOS). UI **responsive par taille de fenêtre, pas par plateforme**. C'est une bibliothèque KMP ; l'APK est produit par `:androidApp`, car AGP 9 interdit `com.android.application` dans un module KMP (ADR 0001).
- `:cli` : **Clikt** pour les commandes, **Mosaic** (runtime Compose dans le terminal) pour la TUI.
- Interop : **API gPodder** en priorité (synchro de l'état de lecture avec AntennaPod), **Open Podcast API** pour les abonnements (sa spec cœur ne couvre encore que ça), **flux RSS par playlist** (enclosures vers les fichiers hébergés), OPML.
- Lecture audio (ADR 0008) : module `:player`, interface `AudioPlayer` injectée par chaque point d'entrée — Media3 (Android, service au premier plan), AVPlayer (iOS, arrière-plan), **JavaFX Media** (desktop et CLI, rien à installer, pas d'Opus) ; `PlaybackController` partagé (position envoyée toutes les 15 s et à la pause, file d'attente côté serveur) ; liens audio **signés** (24 h), car les lecteurs n'envoient pas de jeton.
- **SwiftUI / Liquid Glass** : hors socle, objectif bonus. Liquid Glass n'est obtenu que via des composants natifs (TabView, NavigationStack, toolbars) ; Compose dessine avec Skia et ne peut que l'imiter. Une coque SwiftUI autour des écrans Compose ne marche que sur iOS (pas de Compose natif macOS). Option retenue pour plus tard : un client SwiftUI (iOS + macOS) branché sur le même SDK KMP, avec SKIE pour l'interop Flow/suspend.

## Responsive (repris des wireframes)

| Classe | Condition | Navigation | Contenu |
|---|---|---|---|
| Compact | largeur < 600 dp | barre du bas + mini-lecteur | une colonne |
| Hauteur compacte | hauteur < 480 dp (téléphone paysage) | rail à gauche | deux colonnes, mini-lecteur sur une ligne |
| Moyen | 600–839 dp | rail | une colonne large (à maquetter) |
| Étendu | 840–1199 dp | rail | liste/détail + file d'attente |
| Large | ≥ 1200 dp | tiroir permanent avec abonnements | liste + panneau latéral + lecteur en bas |

Écrans prévus : Accueil (reprendre l'écoute + nouveaux épisodes), Explorer (recherche multi-sources + ajout URL RSS), Fiche podcast, Fiche épisode (notes / chapitres / transcription), Lecteur plein écran, Bibliothèque, Réglages (comptes/serveurs, OPML), Onboarding. Wireframes sur un canvas claude.ai (« Wireframes appli podcast »), refaits proprement sur Figma. Pas encore de design system.

## État actuel et façon de travailler

Le travail suit les étapes de `plan.md`, dans l'ordre. **Étape 1 (POC d'architecture) faite** : chaque client affiche l'écran Accueil à partir d'un `FakePodcastRepository` dans `:shared`, sans backend. Il reste seulement à créer le dépôt GitHub et à protéger `main`. Étape 2 : études 1, 5 et 6 tranchées (ADR 0003 à 0005, POC dans `poc/`), étude 3 tranchée avec l'étape 5 (ADR 0008) ; études 2 et 4 à mener avec l'étape qui en dépend (fiches de cadrage dans `docs/etudes/`). **Étape 3 (contrat) faite** : spécification OpenAPI, serveur sur PostgreSQL avec authentification, SDK avec comptes multiserveurs (ADR 0006), clients branchés sur le serveur. **Étape 4 (abonnements) faite** : lecture des flux protégée et rafraîchie en arrière-plan, Podcast 2.0, recherche proxifiée, OPML, téléchargements mutualisés et purge (ADR 0007) ; Explorer et Bibliothèque dans tous les clients. **Étape 5 (lecture) faite** : étude 3 tranchée (ADR 0008, POC `poc/player`), lecteur sur chaque client (apps, client léger, TUI), lecture en arrière-plan sur mobile, position synchronisée et file d'attente. **Étape 6 (multi-utilisateurs) faite** : premier administrateur par `ADMIN_USERNAME`/`ADMIN_PASSWORD`, comptes gérés par les administrateurs, mot de passe et appareils, tentatives limitées, partage d'abonnements (lecture ou modification) et recommandation d'épisodes, dans chaque client (ADR 0009). **Étape 7 (playlists) faite** : playlists statiques et dynamiques (mots-clés, favoris, notes, aléatoire stable), favoris et notes avancés de l'étape 9, partage, flux RSS à adresse secrète, épisodes des playlists statiques jamais purgés, dans chaque client (ADR 0010). Restent ouverts : l'OPML dans l'app iOS, l'envoi hors ligne des positions. Prochaine étape : l'interopérabilité (étape 8, avec l'étude 2). Les commandes de lancement sont dans `README.md`.

- Avant de coder une étape, relire sa section dans `plan.md` et cocher les cases au fur et à mesure.
- Utiliser les versions les plus récentes des dépendances (Kotlin, Compose Multiplatform, Ktor, Clikt, Mosaic) et les vérifier plutôt que de les supposer ; l'API de Mosaic a changé plusieurs fois.
- Une étape est terminée quand sa « définition de terminé » est vérifiée.

## Conventions

- Code en anglais, documentation en français.
- Décisions importantes dans `docs/adr/`.
- Ne pas faire dépendre `:shared` d'une plateforme ; le code spécifique passe par `expect/actual` ou par des interfaces injectées.
- Package racine `fr.podcast` (provisoire tant que le projet n'a pas de nom).
- Commits logiques et petits, au format Conventional Commits (`feat(module): …`, `build: …`, `docs: …`) ; chaque commit doit compiler.
- `:composeApp:desktopTest` rend les écrans à chaque taille de fenêtre et dans chaque langue dans `composeApp/build/snapshots/` : à regarder après toute modification d'UI.
- **API : spécification d'abord.** Toute ressource, tout lien ou template nouveau passe par `podcast-api.yaml` ; `ContractTest` échoue si la spécification et les routes divergent. Les clients ne construisent pas d'URL : ils suivent les liens de la racine (ADR 0006).
- Serveur en développement : `./gradlew :server:run` (PostgreSQL embarqué, données de démo et comptes dans `DevSeed.kt`, source set `dev` hors distribution). Le navigateur s'y connecte sans mot de passe par `/dev/login/alice` (ou `bob`, `?next=/playlists`), depuis cette machine seulement : c'est ainsi qu'on vérifie le client léger. Les tests serveur utilisent aussi un PostgreSQL embarqué : ni Docker ni installation.
- Ne pas compiler `:shared` ni `:server` pendant qu'un `./gradlew :server:run` tourne : le serveur charge ses classes à la demande et tombe en `NoClassDefFoundError`. Le relancer après.
- Client léger : un refus envoyé à htmx garde son statut et porte la phrase traduite des apps (`respondProblem`) ; un succès répond par une phrase (`respondNotice`), par `HX-Trigger` (`respondChanged`), ou change de page par `HX-Location` (`respondLocation`), ce qui garde le lecteur.
- SQLDelight : une requête avec `RETURNING` n'est exécutée qu'avec `executeAsOne()` ; un paramètre nullable comparé par `=` devient `IS ?`, que PostgreSQL refuse (utiliser un indicateur booléen).
