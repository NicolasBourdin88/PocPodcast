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
2. [ ] **Synchronisation avec clients tiers** : gPodder API vs Open Podcast API, avec un vrai AntennaPod branché sur un serveur minimal. Attention : le cœur de la spec Open Podcast API ne couvre pour l'instant que les abonnements. → fiche `docs/etudes/02-…`.
3. [ ] **Lecture audio multiplateforme** : interface `Player` en `expect/actual` — Media3 (Android), AVPlayer (iOS), VLCJ ou AVFoundation (desktop) ; lecture en arrière-plan et contrôles système. → fiche `docs/etudes/03-…`.
4. [ ] **Client léger** : htmx + kotlinx.html vs Compose Wasm (poids, temps de chargement, effort). → fiche `docs/etudes/04-…` (htmx déjà en place, à confirmer par des mesures).
5. [x] **Persistance** : Exposed vs SQLDelight côté serveur ; SQLDelight / Room KMP côté client pour le hors-ligne. → **PostgreSQL + Flyway + SQLDelight**, SQLDelight aussi côté client (ADR 0004).
6. [x] **Authentification et droits** : sessions (client léger) + jetons (clients riches, multiserveur) ; modèle de partage lecture / modification. → **Argon2id, cookie de session, jetons opaques par appareil, Basic + mots de passe d'application pour gPodder, partages READ/WRITE** (ADR 0005).

---

## Étape 3 — Contrat

- [ ] Spécification de l'API (OpenAPI + conventions hypermédia retenues à l'étape 2).
- [ ] Modèle de données : utilisateurs, podcasts, épisodes, fichiers (mutualisés), états de lecture (par utilisateur), playlists, droits.
- [ ] PostgreSQL + migrations ; remplacer `FakePodcastRepository` côté serveur par une implémentation en base.
- [ ] `RemotePodcastRepository` dans `:shared` (Ktor Client) avec une notion de **compte** (URL du serveur + jeton) pour le multiserveur.

## Étape 4 — Abonnements

- [ ] Ajout par URL, parsing RSS (balises Podcast 2.0 : chapitres, transcriptions).
- [ ] Recherche via Podcast Index (proxifiée par le serveur).
- [ ] Import / export OPML.
- [ ] Modification, annotation, suppression d'abonnements.
- [ ] Worker de rafraîchissement périodique des flux.
- [ ] Téléchargement côté serveur + purge automatique (sauf épisodes marqués ou en playlist).

## Étape 5 — Lecture

- [ ] Lecteur sur chaque client (`expect/actual`), écran lecteur plein écran responsive.
- [ ] Position de lecture synchronisée, file d'attente.
- [ ] Lecture en arrière-plan et contrôles système sur mobile.

## Étape 6 — Multi-utilisateurs

- [ ] Comptes, authentification.
- [ ] Fichiers mutualisés, statut et position par utilisateur.
- [ ] Partage et droits (accès, modification) sur épisodes, playlists, abonnements.

## Étape 7 — Playlists

- [ ] Playlists statiques.
- [ ] Playlists dynamiques : mots-clés, favoris / notes, aléatoire.
- [ ] Partage de playlists.
- [ ] Chaque playlist exposée en **flux RSS** (enclosures vers les fichiers hébergés).

## Étape 8 — Interopérabilité

- [ ] Sous-ensemble de l'API gPodder (abonnements + actions d'épisodes) ; test avec AntennaPod.
- [ ] Open Podcast API pour les abonnements.
- [ ] Vérifier OPML et RSS de playlists avec au moins deux clients tiers.

## Étape 9 — Confort

- [ ] Recherche et filtres multicritères (note, description, date, statut, mots-clés).
- [ ] Notation, favoris, tags.
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
