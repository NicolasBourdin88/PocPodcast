# ADR 0007 — Flux, recherche, téléchargements et purge

- Statut : accepté
- Date : 13 octobre 2026
- Étape : 4 (abonnements)

## Contexte

Avec l'étape 4, le serveur sort sur Internet pour le compte de ses utilisateurs :
- il lit des flux RSS dont **l'adresse est saisie par un utilisateur** ;
- il interroge des annuaires de podcasts ;
- il télécharge des fichiers audio, parfois de plusieurs centaines de Mo.

Il faut donc décider comment lire un flux sans s'exposer (SSRF, XXE, flux énormes), quand le relire, ce qu'on garde des balises Podcast 2.0, comment chercher, et comment télécharger puis purger sans perdre ce qu'un utilisateur veut garder.

## Décision

### Lecture des flux

- **Un podcast par adresse de flux**, partagé par tous les utilisateurs (modèle de données). S'abonner à un flux déjà connu ne le relit pas.
- **Parseur StAX maison** (RSS 2.0, espaces `itunes` et `podcast`), sans bibliothèque RSS :
  - tolérant : date absente ou mal formée, durée en secondes ou `h:mm:ss`, `guid` absent (l'adresse de l'enclosure le remplace) ;
  - **DTD et entités externes désactivées** (XXE) ;
  - un document qui n'est pas du RSS (page HTML, Atom) est refusé : `/problems/not-a-feed`.
- **Protection SSRF** (`UrlGuard`) :
  - schéma `http://` ou `https://` explicite (`https://` est ajouté à une adresse saisie sans schéma) ;
  - adresses publiques seulement : boucle locale, réseaux privés, lien local, CGNAT `100.64.0.0/10`, IPv6 `fc00::/7` et multicast sont refusés (`/problems/forbidden-feed-address`) ;
  - les redirections sont suivies **à la main**, 5 au plus, et chaque étape est vérifiée ;
  - `ALLOW_PRIVATE_FEEDS=true` lève la règle, pour le développement et les tests seulement.
- **Lecture en flux** (`SafeHttp`) : la réponse n'est jamais chargée entière en mémoire. Un flux est limité à 20 Mo (`/problems/feed-too-large`), un fichier audio à 1 Gio, un fichier OPML à 5 Mo.
- **GET conditionnel** : `ETag` et `Last-Modified` sont gardés ; un `304` ne coûte rien.
- **Déménagement** : une chaîne de redirections permanentes (`301`, `308`) ou la balise `itunes:new-feed-url` met à jour l'adresse du podcast.
- **Épisodes** : identifiés par `(podcast, guid)` et mis à jour à chaque lecture. Un épisode sans date garde la date à laquelle il a été vu la première fois.

### Rafraîchissement

- **Tâche de fond** toutes les 5 minutes : elle relit les podcasts suivis dont la dernière tentative date de plus de `REFRESH_INTERVAL_MINUTES` (60 par défaut), les plus anciens d'abord, 4 en parallèle et 100 au plus par tour.
- **Échec** : le podcast passe en `FAILED` avec l'erreur. Ses épisodes restent, et il est retenté à l'intervalle suivant. Les clients affichent « Le flux n'a pas pu être lu ».
- **À la demande** : le template `refresh` d'un podcast (`POST /podcasts/{id}/refresh`).
- **Import OPML** : les flux inconnus sont créés en `PENDING`, puis lus en arrière-plan : l'import n'attend pas la lecture des flux, même s'il en contient des centaines.

### Podcast 2.0

- **Gardés** : `podcast:chapters` (adresse et type) et `podcast:transcript` (plusieurs par épisode : formats et langues, dans l'ordre du flux, qui donne la préférence de l'éditeur).
- **Exposés comme liens HAL** de l'épisode : `chapters`, et `transcript` en tableau, avec `type` et `hreflang`. Les clients les chargent directement chez l'éditeur. Leur affichage arrive avec le lecteur (étape 5).
- Les autres balises (`podcast:person`, `podcast:value`, `podcast:soundbite`…) sont ignorées pour l'instant.

### Recherche

- **`GET /search?q=` proxifiée par le serveur** : les clés restent sur le serveur, les clients ne parlent jamais aux annuaires, et tous les clients voient les mêmes résultats.
- **Sources interrogées en parallèle** :
  - l'API de recherche d'Apple Podcasts, sans clé, toujours ;
  - Podcast Index, si `PODCASTINDEX_KEY` et `PODCASTINDEX_SECRET` sont fournis (authentification SHA-1 de clé + secret + date).
- **Fusion** : les réponses sont entrelacées pour qu'aucun annuaire n'écrase l'autre, dédoublonnées par adresse de flux normalisée (sans schéma, en minuscules, sans barre finale), et chaque résultat dit si l'utilisateur y est déjà abonné (`subscribed`).
- **Pannes** : une source en panne n'empêche pas l'autre. Si aucune ne répond : `502` `/problems/search-failed`.

### Téléchargements

- **Fichiers mutualisés** : un fichier par épisode (`media_files`), sous `MEDIA_DIR/<podcast>/<épisode>.<ext>`, quel que soit le nombre d'utilisateurs qui le demandent.
- **Demandes** :
  - à la main : le template `download` (`POST /episodes/{id}/download`, `202`) ;
  - automatiques : un abonnement avec `autoDownload` demande les `AUTO_DOWNLOAD_LATEST` (1 par défaut) épisodes les plus récents, quand l'option est modifiée, puis à chaque lecture du flux.
- **Tâche de fond unique** : elle prend les demandes une à une, avec les mêmes protections que les flux (SSRF, taille). Le fichier est écrit à côté (`.part`) puis renommé, pour ne jamais servir un fichier incomplet. Un échec passe le fichier en `FAILED` ; une nouvelle demande le retente.
- **Écoute** : `GET /episodes/{id}/audio` sert le fichier du serveur s'il est prêt (requêtes `Range`, `206`), sinon redirige (`302`) vers l'enclosure de l'éditeur.

### Purge

Toutes les heures, le serveur supprime :
1. les fichiers téléchargés depuis plus de `MEDIA_RETENTION_DAYS` (30 par défaut) ;
2. puis, si le quota `MEDIA_MAX_GB` est fixé et dépassé, les plus anciens jusqu'à repasser sous le quota.

Un fichier n'est **jamais** purgé tant que :
- au moins un utilisateur a marqué l'épisode comme **gardé** (`kept`, templates `keep` et `unkeep`, `PUT`/`DELETE /episodes/{id}/kept`) ;
- quelqu'un a l'épisode **en cours d'écoute** et l'a écouté pendant la durée de rétention.

Les épisodes **en playlist** rejoindront ces exceptions à l'étape 7.

Quand le dernier abonné se désabonne, le podcast, ses épisodes et ses fichiers sont supprimés.

## Conséquences

- **Clients simples** : ils ne contactent ni les annuaires ni les flux, seulement leur serveur. Seuls les chapitres et transcriptions sont chargés chez l'éditeur, ce qui lui révèle l'adresse IP du client ; un relais par le serveur reste possible plus tard.
- **Risque résiduel SSRF** : l'adresse est vérifiée, puis résolue à nouveau par le client HTTP au moment de la connexion. Un DNS malveillant (*DNS rebinding*) pourrait changer de réponse entre les deux. Pour un serveur auto-hébergé par un groupe, le risque est accepté ; le corriger demanderait de fixer l'adresse résolue dans le moteur HTTP.
- **Disque** : le volume dépend des demandes, pas du nombre d'utilisateurs. La rétention et le quota sont réglables, et ce qui est gardé échappe au quota : un administrateur peut donc le dépasser.
- **Étape 7** : la requête de purge (`purgeCandidates`) devra exclure les épisodes en playlist.
- **Tests sans réseau** : les flux de test sont servis par un faux hôte (`https://feeds.test`, `MockEngine`), ainsi que les réponses d'Apple Podcasts et de Podcast Index. Le serveur de développement lit, lui, de vrais flux.
