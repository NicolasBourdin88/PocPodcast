# Fiche d'étude 3 — Lecture audio multiplateforme

**Objectif** : une interface `Player` commune dans `:shared` (ou `:composeApp`), implémentée sur chaque plateforme, avec lecture en arrière-plan et contrôles système sur mobile. C'est le risque principal de l'étape 5 (voir les risques du plan) : à étudier tôt.

## Questions à trancher

1. Quelle **interface commune** ? Proposition de départ :
   - `load(url, startPosition)` ;
   - `play()` ;
   - `pause()` ;
   - `seekTo()` ;
   - `setSpeed()` ;
   - `state: StateFlow<PlayerState>` (position, durée, en lecture, en mémoire tampon, erreur).
2. Où vit-elle : `:shared` (logique sans UI) ou `:composeApp` ? Le CLI devra-t-il jouer de l'audio ?
3. Quelle implémentation par plateforme ?
4. Comment assurer la lecture en arrière-plan et les contrôles système (écran verrouillé, casque, notification) ?
5. Comment remonter la position au serveur (fréquence, hors-ligne) ?

## Options par plateforme

| Plateforme | Pistes | Points à vérifier |
|---|---|---|
| Android | **Media3** (ExoPlayer + `MediaSessionService`) | Service au premier plan, notification, Android Auto plus tard |
| iOS | **AVPlayer** via `expect/actual` (Kotlin/Native → AVFoundation) | `AVAudioSession` en catégorie `.playback`, `MPNowPlayingInfoCenter`, `MPRemoteCommandCenter`, mode arrière-plan « audio » dans `Info.plist` |
| Desktop JVM | VLCJ (nécessite VLC installé), JavaFX Media, GStreamer (gst1-java), AVFoundation via JNA (macOS seulement) | Formats MP3/AAC/Opus, poids de la distribution, installation pour l'utilisateur |
| Toutes | Bibliothèques KMP de lecture audio existantes | Maintenance, couverture des contrôles système ; à chercher et évaluer |

## Critères

Formats supportés (MP3, AAC, Opus), vitesse de lecture variable, lecture en arrière-plan, contrôles système, effort d'implémentation, dépendances à installer, poids ajouté à l'application.

## POC suggéré (`poc/player/`)

- Un module KMP avec l'interface `Player`, une implémentation Android (Media3) et une desktop (au moins deux options comparées), puis iOS si quelqu'un a un Mac.
- Une URL de podcast réelle (MP3) : lecture, pause, avance de 30 s, vitesse 1,5×, reprise à une position donnée.
- Android : verrouiller l'écran pendant la lecture, vérifier la notification et les boutons du casque.

## Livrable

ADR « Lecture audio », qui doit contenir :
- l'interface `Player` retenue ;
- l'implémentation choisie pour chaque plateforme, avec ses limites ;
- la stratégie de synchronisation de la position (lien avec l'étude 2).
