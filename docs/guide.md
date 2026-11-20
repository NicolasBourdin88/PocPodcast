# Guide d'utilisation

Ce guide détaille chaque client et chaque fonction ; le [README](../README.md) en donne la vue d'ensemble. Les commandes partent de la racine du dépôt.

## Langues

L'interface existe en français et en anglais. Les textes, les couleurs et la navigation sont définis une seule fois dans `:shared` (voir l'[ADR 0002](adr/0002-chaines-et-ressources-partagees.md)). Chaque client choisit sa langue ainsi :

- Compose : langue du système, ou langue de l'app dans les réglages Android 13 et plus ;
- client léger : en-tête `Accept-Language` du navigateur ;
- CLI : `podcast --lang en list` (par défaut, la langue du système).

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

iOS (Compose) : ouvrir `iosApp/iosApp.xcodeproj` dans Xcode et lancer sur un simulateur. Le serveur y est à `http://localhost:8080`.

iPhone, iPad et Mac (SwiftUI, [ADR 0013](adr/0013-composants-natifs.md)) : ouvrir `appleApp/Podcast.xcodeproj` et lancer le schéma `Podcast` sur un simulateur ou sur « My Mac ». Xcode construit le framework Kotlin par Gradle ; sur Mac, le premier lien prend une dizaine de minutes. En ligne de commande :

```bash
xcodebuild -project appleApp/Podcast.xcodeproj -scheme Podcast -destination 'platform=macOS,arch=arm64' -derivedDataPath build/apple-derived build
```

```bash
open build/apple-derived/Build/Products/Debug/Podcast.app
```

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

La position d'écoute suit l'utilisateur d'un client à l'autre, ainsi que la file d'attente : un épisode commencé sur le téléphone reprend au même endroit sur l'ordinateur ([ADR 0008](adr/0008-lecture-audio.md)).

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

Un administrateur crée les comptes ; chacun change ensuite son nom et son mot de passe, et peut déconnecter un appareil perdu ([ADR 0009](adr/0009-comptes-et-partages.md)). Dans les données de démo, Alice est administratrice.

- **Partager un abonnement** : en lecture seule, ou en lecture et modification (note, téléchargement automatique). Seul le propriétaire se désabonne ou le partage à nouveau.
- **Avatar** : chacun choisit le sien parmi douze, comme sur les plateformes de streaming (Réglages › Profil, ou `podcast profile --avatar`) ; avant tout choix, un avatar déduit de l'identifiant.
- **Recommander un épisode** : à un autre compte, avec un message.
- **Partagés avec moi** : ce que les autres partagent apparaît à part, dans la Bibliothèque. On peut s'abonner soi-même au podcast partagé, ou retirer le partage.
- **Où** :
  - apps : Réglages (profil, mot de passe, appareils, comptes du serveur pour un administrateur), Bibliothèque et page d'un podcast ;
  - client léger : Réglages, Bibliothèque, et « Recommander » sous les nouveaux épisodes de l'Accueil ;
  - CLI : les commandes ci-dessous.

```bash
cli/build/install/podcast/bin/podcast share 2 bob --write --message "Pour le train"
```

Ensuite : `share 2` (avec qui il est partagé), `unshare 2 bob`, `shared` puis `shared remove 1`, `recommend 3 alice`, `profile --name …` et `profile --avatar fox`, `passwd`, `devices` puis `devices revoke 2`, et pour un administrateur `users`, `users add carol --name Carol`, `users role 3 admin`, `users passwd 3`, `users remove 3 --yes`.

## Playlists

Une playlist est **statique** (on choisit ses épisodes et leur ordre) ou **dynamique** : des règles choisissent parmi les abonnements de son propriétaire (mots-clés, favoris, note minimale, non écoutés), du plus récent au plus ancien ou au hasard ([ADR 0010](adr/0010-playlists.md)).

- **Favoris et notes** : le cœur et les étoiles d'un épisode sont propres à chaque compte ; les playlists dynamiques s'en servent.
- **Tout lire** : lance le premier épisode et place les suivants en tête de la file d'attente.
- **Partager** : comme un abonnement, en lecture seule ou en modification (titre, règles, épisodes). Seul le propriétaire la partage, publie son flux ou la supprime.
- **Flux RSS** : le propriétaire publie la playlist à une adresse secrète, à donner à n'importe quelle appli de podcasts. « Nouvelle adresse » rend l'ancienne inutilisable.
- **Purge** : un épisode d'une playlist statique n'est jamais retiré du serveur.
- **Où** :
  - apps : onglet Playlists ; sur chaque épisode et dans le lecteur, le cœur, les étoiles et « Ajouter à une playlist » ;
  - client léger : Playlists dans le menu ; sur l'Accueil, le cœur, la note et « Ajouter à une playlist » sous chaque épisode ;
  - CLI : les commandes ci-dessous.

```bash
cli/build/install/podcast/bin/podcast playlists create Kotlin --keywords "kotlin, ktor" --unplayed
```

Ensuite : `playlists` (les siennes puis celles partagées, numérotées), `playlists show 1`, `playlists play 1`, `playlists create Pour le train` puis `playlists add 2 4` (épisode n° 4 de `list`), `playlists move 2 3 1`, `playlists remove 2 1`, `playlists edit 1 --order random --limit 20`, `playlists shuffle 1`, `playlists share 1 bob --write`, `playlists feed 1` (`--renew`, `--off`), `playlists delete 1 --yes`, `favorite 3` (`--off` pour le retirer), `rate 3 4` (`0` efface la note).

## Recherche, étiquettes et statistiques

Voir l'[ADR 0012](adr/0012-recherche-etiquettes-statistiques.md).

- **Rechercher des épisodes** : parmi ceux de ses abonnements, par mots (titre, description ou nom du podcast), statut, favoris, note minimale, période de publication, podcast et étiquette. Les critères se cumulent.
- **Étiquettes** : jusqu'à 10 par abonnement (« trajets », « tech »), modifiables avec sa note, y compris par un partage en modification. Elles filtrent la Bibliothèque et la recherche.
- **Mon écoute** :
  - le temps écouté au total, sur 7 et sur 30 jours ;
  - les épisodes terminés ;
  - les 12 derniers mois ;
  - les podcasts les plus écoutés.

  Le temps se compte en durée d'épisode : un saut en avant ne compte pas, et les écoutes rapportées par AntennaPod comptent aussi.
- **Où** :
  - apps : dans la Bibliothèque, onglets « Abonnements » (filtre et étiquettes) et « Épisodes » (recherche) ; page d'un podcast pour ses étiquettes ; Réglages pour « Mon écoute » ;
  - client léger : Bibliothèque (filtre, étiquettes, bouton « Rechercher des épisodes »), Réglages ;
  - CLI : les commandes ci-dessous.

```bash
cli/build/install/podcast/bin/podcast episodes kotlin --status new --tag tech
```

Ensuite : `episodes --favorites --min-rating 4`, `episodes --from 2026-10-01 --to 2026-10-31 --podcast 2`, `episodes kotlin --play` (lit ce qui est trouvé, dans l'ordre), `subscriptions --tag tech`, `tags 2` puis `tags 2 trajets tech` (`--clear` pour les retirer), `stats`.

## Clients tiers

Le serveur parle aussi l'**API gPodder** et l'**Open Podcast API** ([ADR 0011](adr/0011-synchronisation-clients-tiers.md)). Un client qui les connaît retrouve les abonnements, les positions et les épisodes écoutés, et nos clients voient ce qu'il en fait.

**AntennaPod** :
1. Dans les Réglages d'un de nos clients, créer un **mot de passe d'application** (« AntennaPod »). Il n'est montré qu'une fois ; le terminal le fait aussi, avec `podcast devices add AntennaPod`.
2. Dans AntennaPod : Settings › Synchronization › Choose synchronization provider › gpodder.net.
3. Saisir l'adresse du serveur, `http://10.0.2.2:8080` depuis l'émulateur Android, l'identifiant et le mot de passe d'application.
4. Créer l'appareil, puis Synchronize now.

Ce qui suit : les abonnements, les positions et l'état « écouté ». La file d'attente, les favoris, les notes et les playlists ne passent pas, car ces API n'ont pas d'équivalent ; une playlist s'écoute en revanche dans n'importe quelle appli par son flux RSS (« Add podcast by RSS address »). Le mot de passe d'application apparaît parmi les appareils et se révoque comme eux.

**Open Podcast API** : `/v1/subscriptions` avec un jeton (`Bearer`) ou un mot de passe d'application (`Basic`). Les formats sont dans la documentation de l'API (http://localhost:8080/docs).

**OPML et flux de playlist dans un autre client**, par exemple newsboat :

```bash
cli/build/install/podcast/bin/podcast opml export podcasts.opml
```

```bash
newsboat -i podcasts.opml
```

L'adresse du flux d'une playlist (`podcast playlists feed 1`) s'ajoute au fichier `urls` de newsboat, ou dans toute appli de podcasts.
