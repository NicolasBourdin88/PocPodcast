# ADR 0008 — Lecture audio

- Statut : accepté
- Date : 14 octobre 2026
- Étape : 5 (lecture), étude 3 ([fiche](../etudes/03-lecture-audio.md))

## Contexte

L'étape 5 fait jouer les épisodes sur chaque client : Android, iOS, desktop, client léger et terminal. Cinq questions se posaient :
- quelle **interface commune** ;
- quel **moteur** par plateforme, en particulier sur desktop, où rien n'est imposé ;
- comment garder la **lecture en arrière-plan** et les **contrôles système** sur mobile ;
- comment un lecteur ouvre un fichier **protégé par le serveur**, alors que la plupart des lecteurs ne savent pas envoyer de jeton ;
- comment **synchroniser la position** entre appareils, ainsi que la file d'attente.

## Options pour le desktop

| Option | Pour | Contre |
|---|---|---|
| VLCJ | Tous les formats, dont Opus | Exige VLC installé sur la machine (absent du poste de développement) |
| **JavaFX Media** | Dépendances Maven seulement, environ 7 Mo pour macOS ; MP3, AAC, vitesse, HTTPS | Pas d'Opus ; deux pièges réseau, voir le POC |
| GStreamer (gst1-java) | Nombreux formats | Installation système |
| AVFoundation par JNA | Natif | macOS seulement |
| Bibliothèques KMP existantes | Une seule API | Centrées sur la vidéo et les composables, peu de contrôle système |

Le POC jetable `poc/player` (`./gradlew -p poc/player run`) a lu de vrais épisodes avec JavaFX 21.0.12, son coupé, sur macOS Apple Silicon. Les mesures sont faites avec le User-Agent de l'application :

| Cas | Adresse donnée à JavaFX | Prêt en | Reprise à 600 s | Vitesse mesurée à 1,5× |
|---|---|---|---|---|
| MP3, chaîne de redirections https (op3 → buzzsprout → CDN) | après résolution | 218 ms | oui | 1,45× |
| AAC (.m4a), redirection anchor.fm → CloudFront | après résolution | 90 ms | oui | 1,46× |
| MP3 derrière une redirection http → https | telle quelle | échec | | |
| MP3 derrière une redirection http → https | après résolution | 109 ms | oui | 1,45× |

Le POC a aussi montré que le toolkit JavaFX démarre sans souci après AWT, comme dans une app Compose Desktop. La vitesse est mesurée sur l'horloge murale ; le léger écart avec 1,5 vient de la mesure.

Il a révélé **deux pièges** :
1. Des CDN (Cloudflare chez buzzsprout) refusent tout User-Agent qui commence par `Java/` ou `curl/`. Or JavaFX télécharge par `java.net`, qui s'annonce `Java/21…`.
2. `java.net` ne suit pas une redirection de `http` vers `https`. C'est pourtant ce que fait le lien du serveur de développement (`http://localhost`) vers le fichier de l'éditeur.

## Décision

### Un module `:player`

- **Interface `AudioPlayer`** : `load(media, début, lecture)`, `play`, `pause`, `seekTo`, `setSpeed`, `stop`, `release`, et un `StateFlow<PlayerState>` (statut, lecture en cours, mise en mémoire tampon, position, durée, vitesse, erreur).
- **Implémentations** :

| Plateforme | Moteur | Ce qu'il apporte |
|---|---|---|
| Android | **Media3** : un `MediaSessionService` possède l'ExoPlayer, l'app lui parle par un `MediaController` | Service au premier plan `mediaPlayback`, notification, écran verrouillé, boutons du casque, focus audio, pause au débranchement des écouteurs |
| iOS | **AVPlayer** | Session audio `playback` et `spokenAudio`, mode d'arrière-plan `audio`, Now Playing (écran verrouillé, Centre de contrôle), commandes à distance (lecture, pause, −15 s, +30 s, position) |
| Desktop et CLI (JVM) | **JavaFX Media 21.0.12**, branche LTS compatible avec le JDK 21 | User-Agent de l'application ; redirections résolues avant de donner l'adresse à JavaFX ; toolkit démarré au premier épisode |
| Démo et tests | **Lecteur simulé** | La position avance sans son ni réseau |

- **Un module à part**, et non `:shared` : le serveur dépend de `:shared` et n'a pas à embarquer JavaFX, alors que le desktop et le CLI partagent la même implémentation JVM.
- **Lecteur injecté** par chaque point d'entrée, comme le stockage des comptes, plutôt qu'un `expect/actual` : Android a besoin d'un `Context`.
- **Client léger** : un `<audio>` et `static/player.js`, sans dépendance. Le pied de page du lecteur porte `hx-preserve`, et la navigation passe par `hx-boost` : changer de page n'interrompt pas la lecture.

### Liens audio signés

- **Lien signé** : le lien `audio` d'un épisode est signé, valable **24 heures**, avec une expiration arrondie à l'heure pour que le lien reste stable un moment. Il se présente sous la forme `/episodes/{id}/audio?expires=…&signature=…`.
- **Signature** : HMAC-SHA256 de l'épisode et de l'expiration. La clé est générée au premier démarrage et gardée en base (`server_secrets`).
- **Lien expiré** : `403` avec le type `/problems/audio-link-expired`.
- **Sans signature** : une session ou un jeton donne toujours accès au fichier.
- **Pourquoi** : JavaFX et `<audio>` ne savent pas envoyer de jeton, et AVPlayer seulement par une option non documentée. Le même mécanisme servira aux enclosures des flux RSS de playlists (étape 7).

### Contrôleur partagé : `PlaybackController`

- **Lancer un épisode** relit l'épisode sur le serveur : on obtient la position enregistrée par un autre appareil et un lien frais. Un épisode écouté repart du début.
- **Position envoyée** :
  - toutes les 15 secondes pendant la lecture ;
  - aussitôt à la pause et au changement d'épisode ;
  - une seconde après un déplacement (une barre de progression en envoie plusieurs à la suite) ;
  - en quittant : desktop, CLI, `onStop` d'Android, onglet masqué dans le navigateur.
- **Conflits** : la dernière écriture gagne. Un envoi qui échoue est retenté au suivant. Il n'y a pas encore de file d'envois hors ligne ; elle viendra avec le cache client SQLDelight (ADR 0004).
- **Fin d'un épisode** : il est marqué écouté, le serveur le retire de la file, puis l'épisode suivant de la file démarre.
- **Erreur de lecture** : un seul nouvel essai avec un lien frais (lien expiré, CDN capricieux), puis un message.
- **Position au-delà de la durée annoncée** : acceptée par le serveur, jusqu'à 24 heures, car la durée donnée par le flux est approximative, voire nulle quand le flux ne la donne pas. Le lecteur affiche la durée réelle du fichier.
- **Un seul contrôleur par processus** (Application Android, singletons iOS et desktop) : la lecture survit aux écrans.

### File d'attente

- **Côté serveur et par utilisateur**, donc la même sur tous les appareils. Positions denses à partir de 0.
- **Templates** : `enqueue` (position facultative, `0` pour « lire ensuite »), `dequeue`, `move` et `clear-queue`.
- **Ce qui retire un épisode de la file** :
  - le lire ;
  - le marquer comme écouté ;
  - se désabonner de son podcast.
- **Purge** : les fichiers des épisodes en file ne sont jamais purgés. L'ADR 0007 est étendue en ce sens.

## Conséquences

- **Formats** : Opus n'est pas lu sur desktop ni dans le CLI (JavaFX). Il est rare dans les podcasts ; le client affiche « Impossible de lire cet épisode ».
- **Testé sur macOS seulement** pour le desktop. Sous Linux, JavaFX Media peut demander des bibliothèques système (FFmpeg) pour l'AAC, à vérifier.
- **Paquet desktop** : il doit inclure les modules `java.net.http` et `jdk.jfr`, sans quoi le premier épisode échoue (`suggestRuntimeModules`).
- **CLI sur macOS** : JavaFX fait apparaître une icône Java dans le Dock pendant la lecture.
- **Android** : la configuration réseau n'autorise le HTTP en clair que vers les serveurs de développement. Une enclosure en `http://`, rare aujourd'hui, ne se lit donc que si le serveur a téléchargé le fichier, qu'il sert alors lui-même.
- **Négociation htmx** : htmx accepte n'importe quel type. Une requête htmx reçoit donc du HTML, sauf si elle demande explicitement du JSON. C'est indispensable à la navigation boostée.
- **Vérifié de bout en bout le 14 octobre 2026**, sur les serveurs de développement :
  - CLI : lecture, pause, position envoyée ;
  - Android : session `PLAYING`, notification de service au premier plan, pause par touche média, fin d'épisode puis épisode suivant de la file ;
  - iOS : lecture, puis 30 secondes en arrière-plan avec la position qui continue d'avancer sur le serveur ;
  - navigateur : changement de page sans coupure ;
  - à chaque fois, la position reprise sur un autre client.
- **Non vérifié** : la hauteur de la voix à 1,5× n'a pas été écoutée, les tests étant faits son coupé ou sans casque. L'algorithme choisi sur iOS (`timeDomain`) est fait pour la voix.
