# Plan — Système d'écoute de podcasts

Projet de master, groupe mixte apprentis / non-apprentis, d'octobre à mars.
Référence fonctionnelle : PinePods. Références techniques : Open Podcast API, Podcast Index.

Le plan est découpé en **étapes**. Chaque étape a un objectif, des tâches et une définition de « terminé ». On ne commence une étape que quand la précédente est terminée (sauf les études, qui tournent en parallèle de l'étape 1).

---

## Vue d'ensemble

| Étape | Période | Objectif |
|---|---|---|
| 1. POC d'architecture | mi-oct. → fin oct. | Toutes les technos démarrent et affichent le premier écran, sans backend |
| 2. Études techniques | oct. → début nov. (en parallèle) | Trancher les choix ouverts |
| 3. Contrat | novembre | API hypermédia spécifiée, modèle de données, persistance |
| 4. Abonnements | nov. → déc. | URL, recherche, OPML, rafraîchissement, téléchargement |
| 5. Lecture | décembre | Lecteur sur chaque client, position synchronisée, file d'attente |
| 6. Multi-utilisateurs | janvier | Comptes, fichiers mutualisés, états par utilisateur, droits |
| 7. Playlists | janvier | Statiques, dynamiques, partage, flux RSS de playlist |
| 8. Interopérabilité | février | gPodder API, Open Podcast API, test avec AntennaPod |
| 9. Confort | février | Recherche multicritère, stats, notation, purge auto |
| 10. Finition | mars | Stabilisation, tests, doc, démo. Bonus : client SwiftUI |

---

## Architecture cible

```
                  ┌───────────────┐ ┌──────────────┐ ┌───────────┐
                  │ Client mobile │ │ Client riche │ │ TUI / CLI │
                  │ Compose MP    │ │ Compose Desk.│ │ Clikt +   │
                  │ Android / iOS │ │ JVM          │ │ Mosaic    │
                  └──────┬────────┘ └──────┬───────┘ └─────┬─────┘
                         └─────────┬───────┴───────────────┘
┌──────────────┐          ┌────────▼─────────┐
│ Client léger │          │  SDK client KMP  │  :shared
│ HTML + htmx  │          │ modèles, API,    │
└──────┬───────┘          │ cache, comptes   │
       │                  └────────┬─────────┘
┌──────▼───────────────────────────▼──────────────────────────┐
│ Service Ktor                                      :server   │
│  API hypermédia (HAL JSON / HTML selon Accept)              │
│  Compatibilité : gPodder API, Open Podcast API, RSS, OPML   │
│  Workers : rafraîchissement des flux, téléchargements       │
└──────────────────────────┬──────────────────────────────────┘
                 PostgreSQL + stockage des fichiers audio
```

| Module Gradle | Cibles | Rôle |
|---|---|---|
| `:shared` | JVM, Android, iOS | Modèles, interfaces de dépôt, états d'écran, client d'API |
| `:composeApp` | Android, iOS, Desktop (JVM) | UI Compose partagée, responsive par taille de fenêtre |
| `:server` | JVM | Ktor : API hypermédia + client léger HTML/htmx |
| `:cli` | JVM (Native plus tard) | Commandes Clikt + TUI Mosaic |
| `iosApp/` | iOS | Coque Swift qui héberge l'UI Compose |

---

## Étape 1 — POC d'architecture

**Objectif** : prouver que chaque technologie compile, démarre et consomme **le même module partagé**. Chaque client affiche **son premier écran (l'Accueil)**. Pas de vrai backend, pas de réseau, pas de base de données : des données factices dans `:shared` alimentent tout.

**Durée visée** : 1 à 2 semaines.

### 1.1 Initialiser le projet

- [x] Générer le projet avec le **Kotlin Multiplatform wizard** (kmp.jetbrains.com) : Android, iOS (UI partagée), Desktop, Server. Récupérer notamment le projet Xcode `iosApp/`. *(Squelette écrit à la main ; `iosApp/` repris du KMP-App-Template officiel.)*
- [x] Renommer / réorganiser les modules en `:shared`, `:composeApp`, `:server`, et ajouter `:cli`. *(Plus `:androidApp`, imposé par AGP 9 : voir ADR 0001.)*
- [x] Centraliser les versions dans `gradle/libs.versions.toml` (versions les plus récentes au moment de la création).
- [x] Activer les accesseurs typés (`projects.shared`).
- [ ] Créer le dépôt Git, protéger `main`, ajouter une CI GitHub Actions qui build JVM + Android. *(Dépôt local et CI faits ; reste à créer le dépôt GitHub et protéger `main`.)*

### 1.2 Module partagé `:shared`

- [x] Modèles sérialisables : `Podcast` (id, titre, auteur, URL du flux, pochette) et `Episode` (id, podcastId, titre, date affichable, durée, position, statut `NEW / IN_PROGRESS / PLAYED`), avec des propriétés calculées `progress`, `remainingMinutes`, `durationLabel`.
- [x] Interface `PodcastRepository` : `subscriptions()`, `podcast(id)`, `latestEpisodes(limit)`, `inProgress()`.
- [x] Implémentation `FakePodcastRepository` : 4 podcasts et 5 épisodes en dur, dont 2 en cours.
- [x] Cas d'usage `LoadHomeState` qui produit un `HomeState(resume, latest)` : **le même état pour tous les clients**, seul le rendu change.
- [x] Un test unitaire commun sur `LoadHomeState`.

### 1.3 Client mobile et riche `:composeApp`

- [x] Écran Accueil Compose dans `commonMain` : « Reprendre l'écoute » (carte avec progression) + « Nouveaux épisodes » (liste) + mini-lecteur factice.
- [x] Classes de taille de fenêtre calculées depuis l'espace disponible (voir tableau responsive dans `CLAUDE.md`) :
  - compact → barre de navigation en bas, une colonne ;
  - hauteur compacte (téléphone paysage) → rail, deux colonnes ;
  - moyen / étendu → rail, deux colonnes, file d'attente à partir d'étendu ;
  - large → tiroir permanent.
- [x] Points d'entrée : `MainActivity` (Android, module `:androidApp`), `main()` avec `Window` (desktop), `MainViewController()` + `iOSApp.swift` (iOS).

### 1.4 Service et client léger `:server`

- [x] Ktor avec une route `/` qui négocie selon l'en-tête `Accept` :
  - `text/html` → page Accueil en HTML (kotlinx.html), responsive en CSS (barre en bas sur mobile, barre latérale à partir de 840 px) ;
  - `application/hal+json` → même contenu en HAL : `_links` (self, podcasts, episodes) et `_embedded` (resume, latest), chaque épisode ayant ses propres liens (self, podcast, play).
- [x] Une route fragment `/fragments/latest` et un bouton **htmx** qui recharge la liste sans recharger la page.
- [x] Routes `/podcasts` et `/episodes` en HAL.
- [x] Deux tests Ktor (`testApplication`) : HTML pour un navigateur, HAL pour un client d'API.

### 1.5 CLI / TUI `:cli`

- [x] `podcast list` (Clikt) : affiche en texte « Reprendre l'écoute » et « Nouveaux épisodes ».
- [x] `podcast tui` (Mosaic) : même écran, avec une barre de progression en caractères.

### Définition de « terminé »

| # | Vérification | Commande |
|---|---|---|
| P1 | `:shared` compile pour JVM, Android, iOS ; test vert | `./gradlew :shared:allTests` |
| P2 | Accueil sur Android, barre en portrait, rail en paysage | `./gradlew :androidApp:installDebug` |
| P3 | Accueil sur desktop ; en redimensionnant : barre → rail → tiroir | `./gradlew :composeApp:run` |
| P4 | Accueil dans le simulateur iOS | Xcode sur `iosApp` |
| P5 | Accueil HTML + rafraîchissement htmx | `./gradlew :server:run`, http://localhost:8080 |
| P6 | Même contenu en HAL | `curl -H "Accept: application/hal+json" localhost:8080/` |
| P7 | Liste texte puis TUI | `./gradlew :cli:run --args="list"`, puis `:cli:installDist` et `cli/build/install/podcast/bin/podcast tui` |

Et : le même objet `Episode` de `:shared` est visible dans les 5 rendus, le README explique comment lancer chaque cible, et une démo de 5 minutes est possible en séance.

---

## Étape 2 — Études techniques (non-apprentis, en parallèle de l'étape 1)

Chaque étude produit une note de 1 à 2 pages dans `docs/adr/` : options, critères, POC jetable (dans `poc/`), décision. Suivi et fiches de cadrage : `docs/etudes/`.

1. [x] **Format hypermédia** : HAL vs Siren vs JSON:API. Critère clé : peut-on décrire des *actions* (formulaires) et pas seulement des liens ? → **HAL + HAL-FORMS** (ADR 0003).
2. [x] **Synchronisation avec clients tiers** : gPodder API vs Open Podcast API, avec un vrai AntennaPod branché sur un serveur minimal. Attention : le cœur de la spec Open Podcast API ne couvre pour l'instant que les abonnements. → **sous-ensemble gPodder observé sur AntennaPod 3.12.3 (connexion puis cookie `sessionid`, abonnements par deltas, actions `play`), la date la plus récente gagne, Open Podcast API pour les abonnements, mots de passe d'application** (ADR 0011, POC `poc/gpodder-sync`).
3. [x] **Lecture audio multiplateforme** : interface `Player` en `expect/actual` — Media3 (Android), AVPlayer (iOS), VLCJ ou AVFoundation (desktop) ; lecture en arrière-plan et contrôles système. → **module `:player` : Media3, AVPlayer, JavaFX Media sur desktop et CLI (pas de VLC à installer), liens audio signés, contrôleur partagé** (ADR 0008, POC `poc/player`).
4. [ ] **Client léger** : htmx + kotlinx.html vs Compose Wasm (poids, temps de chargement, effort). → fiche `docs/etudes/04-…` (htmx déjà en place, à confirmer par des mesures).
5. [x] **Persistance** : Exposed vs SQLDelight côté serveur ; SQLDelight / Room KMP côté client pour le hors-ligne. → **PostgreSQL + Flyway + SQLDelight**, SQLDelight aussi côté client (ADR 0004).
6. [x] **Authentification et droits** : sessions (client léger) + jetons (clients riches, multiserveur) ; modèle de partage lecture / modification. → **Argon2id, cookie de session, jetons opaques par appareil, Basic + mots de passe d'application pour gPodder, partages READ/WRITE** (ADR 0005).

---

## Étape 3 — Contrat

- [x] Spécification de l'API (OpenAPI + conventions hypermédia retenues à l'étape 2). → `server/src/main/resources/openapi/podcast-api.yaml` (servie sur `/docs`), `docs/api/conventions.md`.
- [x] Modèle de données : utilisateurs, podcasts, épisodes, fichiers (mutualisés), états de lecture (par utilisateur), playlists, droits. → `docs/api/modele-de-donnees.md` ; les tables arrivent avec l'étape qui les utilise.
- [x] PostgreSQL + migrations ; remplacer `FakePodcastRepository` côté serveur par une implémentation en base. → Flyway + SQLDelight (`UserCatalog`), authentification (ADR 0005), client léger avec connexion.
- [x] `RemotePodcastRepository` dans `:shared` (Ktor Client) avec une notion de **compte** (URL du serveur + jeton) pour le multiserveur. → ADR 0006 ; branché dans les apps Compose et le CLI.

### Définition de « terminé »

| # | Vérification | Commande |
|---|---|---|
| C1 | La spécification et le serveur correspondent dans les deux sens, chaque réponse respecte son schéma | `./gradlew :server:test` (`ContractTest`) |
| C2 | Le SDK de `:shared` fonctionne contre le vrai serveur (connexion, Accueil, actions HAL-FORMS, déconnexion) | `./gradlew :server:test` (`SdkTest`) |
| C3 | Le serveur démarre sur PostgreSQL avec les données de démo, documentation sur `/docs` | `./gradlew :server:run` |
| C4 | Client léger : connexion, Accueil de l'utilisateur, action htmx « Marquer comme écouté » | http://localhost:8080 |
| C5 | Apps (Android, iOS, desktop) : connexion, Accueil chargé du serveur, deux comptes sur deux serveurs | voir `README.md` |
| C6 | CLI : `login`, `accounts`, `use`, `list`, `tui` sur deux serveurs | `podcast login` |

## Étape 4 — Abonnements

- [x] Ajout par URL, parsing RSS (balises Podcast 2.0 : chapitres, transcriptions). → parseur StAX protégé (XXE, SSRF, taille), GET conditionnel, déménagements de flux ; chapitres et transcriptions exposés en liens HAL (ADR 0007).
- [x] Recherche via Podcast Index (proxifiée par le serveur). → `GET /search` : Apple Podcasts toujours, Podcast Index avec `PODCASTINDEX_KEY`/`PODCASTINDEX_SECRET`.
- [x] Import / export OPML. → lien `opml` des abonnements ; client léger, apps desktop et Android, CLI (pas encore dans l'app iOS).
- [x] Modification, annotation, suppression d'abonnements. → note, téléchargement automatique, désabonnement ; le podcast part avec son dernier abonné.
- [x] Worker de rafraîchissement périodique des flux. → toutes les 5 min, flux relus après `REFRESH_INTERVAL_MINUTES` ; relecture à la demande (template `refresh`).
- [x] Téléchargement côté serveur + purge automatique (sauf épisodes marqués ou en playlist). → fichiers mutualisés, `GET /episodes/{id}/audio` avec `Range` ; purge par âge et quota, sauf épisodes gardés ou en cours d'écoute. **L'exception « en playlist » s'ajoutera à l'étape 7**, avec les playlists.

### Définition de « terminé »

| # | Vérification | Commande |
|---|---|---|
| A1 | Lecture de flux : Podcast 2.0, flux invalides, XXE, adresses privées refusées, déménagements | `./gradlew :server:test` (`FeedTest`, `SubscriptionsTest`) |
| A2 | Recherche fusionnée, une source en panne n'empêche pas l'autre ; OPML aller-retour | `./gradlew :server:test` (`SearchTest`, `OpmlTest`) |
| A3 | Téléchargement (redirections, `Range`) et purge (âge, quota, épisodes gardés ou en cours) | `./gradlew :server:test` (`DownloadsTest`) |
| A4 | Contrat à jour et SDK de bout en bout (abonnement, note, épisodes Podcast 2.0, téléchargement, OPML, refus typés) | `./gradlew :server:test` (`ContractTest`, `SdkTest`) |
| A5 | Serveur de développement alimenté par de vrais flux, rafraîchis en arrière-plan | `./gradlew :server:run` |
| A6 | Client léger : Explorer (recherche, ajout par adresse), Bibliothèque (note, téléchargement automatique, désabonnement, OPML) | http://localhost:8080 |
| A7 | Apps : Explorer, Bibliothèque et page d'un podcast, en compact et en deux panneaux | `./gradlew :composeApp:desktopTest` (captures), puis chaque app |
| A8 | CLI : `search`, `subscribe`, `subscriptions`, `note`, `unsubscribe`, `opml export`/`import` | voir `README.md` |

## Étape 5 — Lecture

- [x] Lecteur sur chaque client (`expect/actual`), écran lecteur plein écran responsive. → interface `AudioPlayer` injectée par chaque point d'entrée (ADR 0008) ; mini-lecteur, barre de lecture sur grand écran, lecteur plein écran à une ou deux colonnes ; `<audio>` conservé de page en page dans le client léger ; lecteur dans la TUI.
- [x] Position de lecture synchronisée, file d'attente. → `PlaybackController` partagé (toutes les 15 s et à la pause, reprise là où un autre appareil s'est arrêté) ; file d'attente côté serveur (`/queue`), épisode suivant à la fin d'un épisode.
- [x] Lecture en arrière-plan et contrôles système sur mobile. → service Media3 (notification, écran verrouillé, casque) ; mode d'arrière-plan audio, Now Playing et commandes à distance sur iOS.

### Définition de « terminé »

| # | Vérification | Commande |
|---|---|---|
| L1 | Contrôleur : position envoyée (périodique, pause, déplacement), épisode suivant de la file, nouvel essai avec un lien frais | `./gradlew :player:jvmTest` |
| L2 | Liens audio signés (expiration, falsification), file d'attente (ordre, retraits, purge), contrat à jour | `./gradlew :server:test` (`AudioLinksTest`, `QueueTest`, `ContractTest`, `SdkTest`) |
| L3 | Client léger : lecteur conservé entre les pages, boutons de lecture, file d'attente par htmx | `./gradlew :server:test` (`WebTest`), puis http://localhost:8080 |
| L4 | Apps : mini-lecteur, barre de lecture, lecteur plein écran à une ou deux colonnes, file d'attente | `./gradlew :composeApp:desktopTest` (captures `player-*`), puis chaque app |
| L5 | Android : lecture en arrière-plan avec notification, touche média, épisode suivant de la file | émulateur, `adb shell dumpsys media_session` |
| L6 | iOS : la lecture continue en arrière-plan, la position avance sur le serveur | simulateur |
| L7 | CLI : `play`, `queue`, TUI (espace, flèches, vitesse, suivant) | `podcast play` (voir `README.md`) |

## Étape 6 — Multi-utilisateurs

- [x] Comptes, authentification. → premier administrateur par l'environnement, comptes gérés par les administrateurs, changement de mot de passe (les autres appareils sont déconnectés), appareils, tentatives limitées (ADR 0009).
- [x] Fichiers mutualisés, statut et position par utilisateur. → acquis aux étapes 4 et 5 (catalogue et fichiers mutualisés, `playback_states` et file d'attente par utilisateur) ; vérifié avec deux comptes.
- [x] Partage et droits (accès, modification) sur épisodes, playlists, abonnements. → abonnements en lecture ou en modification, épisodes recommandés avec un message, une seule règle `can()` ; dans chaque client. **Les playlists se partageront à l'étape 7**, avec la même table et les mêmes routes.

### Définition de « terminé »

| # | Vérification | Commande |
|---|---|---|
| M1 | Comptes : annuaire, gestion par les administrateurs, mot de passe, appareils, tentatives limitées, premier administrateur | `./gradlew :server:test` (`AccountManagementTest`) |
| M2 | Partages : lecture seule, modification, recommandation, 404 et 403, partages supprimés avec ce qu'ils partagent | `./gradlew :server:test` (`SharingTest`, `ContractTest`) |
| M3 | SDK de bout en bout (comptes, partages, refus traduits) ; la démo se comporte comme un serveur | `./gradlew :server:test` (`SdkTest`), `./gradlew :shared:jvmTest` |
| M4 | Client léger : Réglages (`/me`), partages dans la Bibliothèque, recommandation depuis l'Accueil, refus affichés | `./gradlew :server:test` (`WebTest`), puis http://localhost:8080 |
| M5 | Apps : Réglages, « Partagés avec moi », page d'un podcast selon les droits, partage et recommandation | `./gradlew :composeApp:desktopTest` (captures `settings-*`, `shared-*`), puis chaque app |
| M6 | CLI : `profile`, `passwd`, `devices`, `users`, `share`, `shared`, `unshare`, `recommend` | voir `README.md` |
| M7 | Deux comptes sur un serveur : chacun sa progression et sa file, le partage visible de l'autre côté | Alice et Bob (`DevSeed.kt`) |

## Étape 7 — Playlists

- [x] Playlists statiques. → épisodes choisis et ordonnés ; un épisode qui y figure n'est jamais purgé (ADR 0010).
- [x] Playlists dynamiques : mots-clés, favoris / notes, aléatoire. → règles calculées sur les abonnements du propriétaire, ordre aléatoire stable jusqu'au prochain « Mélanger ». **Favoris et notes sont avancés de l'étape 9** pour cela.
- [x] Partage de playlists. → même table et même règle `can()` qu'à l'étape 6 : lecture, ou modification du titre, des règles et des épisodes.
- [x] Chaque playlist exposée en **flux RSS** (enclosures vers les fichiers hébergés). → adresse secrète publiée par le propriétaire, renouvelable ; enclosures servies par le serveur et limitées aux épisodes de la playlist.

### Définition de « terminé »

| # | Vérification | Commande |
|---|---|---|
| P1 | Favoris et notes par utilisateur ; playlists statiques et dynamiques (mots-clés, favoris, note minimale, non écoutés, ordre), aléatoire stable jusqu'au mélange, 409 pour ce qu'une dynamique ne permet pas | `./gradlew :server:test` (`EpisodeMarksTest`, `PlaylistsTest`) |
| P2 | Partage d'une playlist : lecture seule, modification, 404 et 403 | `./gradlew :server:test` (`SharingTest`, `ContractTest`) |
| P3 | Flux RSS : adresse secrète, nouvelle adresse, retrait, relu par l'analyseur RSS du serveur ; enclosures limitées à la playlist | `./gradlew :server:test` (`PlaylistFeedTest`) |
| P4 | Purge : un épisode d'une playlist statique n'est pas purgé | `./gradlew :server:test` (`DownloadsTest`) |
| P5 | SDK de bout en bout ; la démo a ses playlists, dont une partagée par Bob | `./gradlew :server:test` (`SdkTest`), `./gradlew :shared:jvmTest` |
| P6 | Client léger : pages Playlists, « Tout lire », partage, flux, ajout et note depuis l'Accueil | `./gradlew :server:test` (`WebTest`), puis http://localhost:8080/playlists |
| P7 | Apps : destination Playlists, création et modification, ajout depuis un épisode, cœur et étoiles | `./gradlew :composeApp:desktopTest` (captures `playlist*`), puis chaque app |
| P8 | CLI : `playlists …`, `favorite`, `rate` | `./gradlew :cli:test`, voir `README.md` |

## Étape 8 — Interopérabilité

- [ ] Sous-ensemble de l'API gPodder (abonnements + actions d'épisodes) ; test avec AntennaPod.
- [ ] Open Podcast API pour les abonnements.
- [ ] Vérifier OPML et RSS de playlists avec au moins deux clients tiers.

## Étape 9 — Confort

- [ ] Recherche et filtres multicritères (note, description, date, statut, mots-clés).
- [ ] Notation, favoris, tags. → notation et favoris faits à l'étape 7 (ADR 0010) ; restent les tags.
- [ ] Statistiques d'écoute.

## Étape 10 — Finition

- [ ] Stabilisation, tests, documentation utilisateur et technique.
- [ ] Démo finale.
- [ ] Bonus : client SwiftUI (iOS + macOS, Liquid Glass) branché sur le SDK `:shared`.

---

## Répartition proposée

Découpage **par couche**, chaque binôme mêlant apprenti et non-apprenti :

- **Service & API** : Ktor, base, workers, conventions hypermédia.
- **Interop** : gPodder / Open Podcast API, RSS de playlists, OPML.
- **SDK + clients Compose** : `:shared`, mobile, desktop, responsive.
- **Client léger + CLI/TUI** : HTML/htmx, Clikt, Mosaic.

Règle : toute modification de modèle passe par `:shared` et par une revue d'un membre d'une autre couche.

---

## Risques

| Risque | Impact | Parade |
|---|---|---|
| Groupe peu à l'aise avec Kotlin | Démarrage lent | Contrat d'API écrit tôt, binômes mixtes, POC simples |
| Lecture audio par plateforme | Retard sur l'étape 5 | Étude n°3 dès octobre ; desktop en dernier |
| Open Podcast API incomplète | Interop partielle | API gPodder en priorité pour AntennaPod |
| iOS nécessite un Mac | Cible non testée | Identifier qui a un Mac ; CI macOS |
| Périmètre très large | Fonctions bâclées | Prioriser étapes 4 à 6 ; playlists dynamiques et stats en dernier |
