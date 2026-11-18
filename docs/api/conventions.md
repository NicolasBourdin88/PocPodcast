# Conventions de l'API hypermédia

Règles communes à toutes les ressources du service. Elles appliquent les ADR 0003 (HAL + HAL-FORMS), 0005 (authentification) et 0009 (comptes et partages). Le détail de chaque ressource est dans la spécification OpenAPI : `server/src/main/resources/openapi/podcast-api.yaml`, consultable sur http://localhost:8080/docs quand le serveur tourne.

## Une URL, plusieurs représentations

Chaque ressource a **une seule URL** et le format est négocié avec l'en-tête `Accept` :

| `Accept` | Représentation | Pour qui |
|---|---|---|
| `application/prs.hal-forms+json` | HAL + `_templates` (actions possibles dans l'état courant) | SDK `:shared` (Compose, CLI) |
| `application/hal+json` ou `application/json` | HAL, en lecture seule | Outils et clients tiers |
| `text/html` | Page ou fragment HTML (client léger) | Navigateur |

Les réponses portent `Vary: Accept, Accept-Language`. Le JSON est **neutre en langue** : seul le HTML est traduit (ADR 0002).

Sans type précis (`*/*`), la réponse est du JSON, sauf pour htmx : une requête `HX-Request` reçoit du HTML tant qu'elle ne demande pas explicitement du JSON. C'est ce qui permet la navigation boostée du client léger.

Pages HTML existantes : l'Accueil (`/`), Explorer (`/search`), la Bibliothèque (`/subscriptions`), les Playlists (`/playlists` et `/playlists/{id}`) et les Réglages (`/me`). Les autres arrivent avec leurs fonctionnalités.

**Client léger (htmx)** : une action envoyée par htmx (`HX-Request`) reçoit un **fragment HTML** à insérer, par exemple la phrase qui dit ce qui a été fait.
- **Refus** : il garde son statut (403, 404, 422, 429), mais son corps est la phrase que les apps afficheraient, traduite avec la même correspondance que le SDK. La page configure htmx (`responseHandling`) pour insérer ces réponses-là à côté du formulaire.
- **Refus plus anciens** : l'abonnement à un flux et l'import OPML répondent encore `200` avec un fragment d'erreur.
- **Rechargements** : quand une action change une liste, l'en-tête `HX-Trigger` demande à la page de la recharger : `subscriptions-changed`, `queue-changed`, `episodes-changed`, `shares-changed`, `users-changed`, `devices-changed`, `playlists-changed`. L'élément qui se recharge porte `hx-disinherit="*"` : ses formulaires et ses liens gardent ainsi leur propre cible au lieu d'hériter de la sienne.
- **Changement de page** : quand l'action crée, renomme ou supprime ce que montre la page (une playlist), la réponse porte `HX-Location` vers la page à afficher. htmx la charge comme un lien boosté, sans arrêter le lecteur.

**Lecteur du client léger** : un `<audio>` dans un pied de page `hx-preserve`, conservé de page en page grâce à `hx-boost` sur la navigation, et piloté par `static/player.js`. Ses requêtes de modification portent aussi `HX-Request`.

## Point d'entrée et navigation

- **Racine** : un client ne connaît que l'URL du serveur. Il lit la racine `GET /` et suit les liens. Il ne construit jamais une URL lui-même, à l'exception des liens à gabarit.
- **Racine sans authentification** : nom du serveur, version de l'API, lien `tokens` et template `create-token` (comment se connecter).
- **Racine authentifiée** : liens `subscriptions`, `queue`, `episodes`, `search` (gabarit `/search{?q}`), `me`, `users`, `shared`, `playlists`, et l'état de l'Accueil embarqué (`resume`, `latest`). Elle fournit aussi les gabarits `episode` (`/episodes/{id}`), `podcast` (`/podcasts/{id}`), `subscription` (`/subscriptions/{id}`), `share` (`/shares/{id}`) et `playlist` (`/playlists/{id}`), pour retrouver une ressource à partir de son identifiant sans construire l'URL.
- **Liens** : `_links` contient toujours `self`. Les noms de relation sont en minuscules, avec un tiret si besoin.
- **Liens à gabarit** : ils suivent la RFC 6570 et portent `"templated": true`, par exemple `/episodes{?status,offset,limit}`.
- **Ressources embarquées** : `_embedded`. Un épisode embarque son podcast, pour qu'un client puisse l'afficher sans autre requête.
- **Ressources externes** : un lien peut pointer hors du serveur et porter `type` et `hreflang`, comme les chapitres et transcriptions Podcast 2.0 d'un épisode. Une relation qui a plusieurs liens (`transcript`) est un **tableau**, dans l'ordre de préférence de l'éditeur.

## Collections et pagination

```json
{
  "count": 20, "total": 57, "offset": 0, "limit": 20,
  "_links": { "self": { "href": "/episodes?offset=0&limit=20" }, "next": { "href": "/episodes?offset=20&limit=20" } },
  "_embedded": { "episodes": [ … ] }
}
```

- **Paramètres** : `offset` (défaut 0) et `limit` (défaut 20, maximum 100).
- **Liens de page** : `next` et `prev` ne sont présents que s'il y a une page suivante ou précédente.

## Actions (HAL-FORMS)

- **Où** : les actions sont décrites dans `_templates`, **nommées** (`update-progress`, `mark-played`…), et seulement quand elles sont permises dans l'état courant.
  - Exemple : `mark-played` disparaît quand l'épisode est écouté et `mark-unplayed` apparaît.
- **Propriétés d'un template** :
  - `method` et `target` sont toujours renseignés ;
  - `contentType` vaut `application/json` ;
  - les `properties` utilisent le sous-ensemble `name`, `required`, `readOnly`, `type`, `value`, `min`, `max`, `regex`, `options`.
- **Ressources embarquées** : elles portent leurs propres `_templates`. L'Accueil peut ainsi proposer « Marquer comme écouté » sans requête supplémentaire.
- **Titres** : les templates n'ont pas de `title`. Le nom est le contrat, et chaque client l'affiche avec ses propres textes traduits.
- **Côté serveur** : un seul modèle `Affordance` produit les templates HAL-FORMS *et* les boutons htmx du client léger.

## Authentification

| Client | Mécanisme |
|---|---|
| SDK, CLI, clients riches | `Authorization: Bearer <jeton>`. Le jeton s'obtient par `POST /auth/tokens` (`username`, `password`, `deviceName`) et se révoque par `DELETE /auth/tokens/current`. |
| Client léger | Cookie de session après le formulaire `/login`. Les requêtes qui modifient l'état exigent l'en-tête `HX-Request` (envoyé par htmx), en plus de `SameSite=Lax`, contre le CSRF. |
| Clients gPodder (étape 8) | HTTP Basic, mot de passe ou mot de passe d'application |

- **Pas authentifié** : `401` avec `WWW-Authenticate: Bearer realm="podcast"`. Le client léger est redirigé vers `/login`.
- **Ressource invisible** : `404`, y compris si elle existe mais n'est pas visible pour l'utilisateur.
- **Ressource visible, action interdite** : `403`, par exemple modifier un abonnement partagé en lecture seule, ou gérer les comptes sans être administrateur.
- **Trop d'essais** : après 5 mauvais mots de passe en 15 minutes, `429` avec `Retry-After` (ADR 0009).

## Erreurs

Le format des erreurs est **Problem Details** (RFC 9457), en `application/problem+json` :

```json
{ "type": "about:blank", "title": "Not Found", "status": 404, "detail": "Episode 42 not found" }
```

Les erreurs de validation (`422`) ajoutent `errors: [{ "field": "positionSeconds", "message": "…" }]`.

Une action permise mais incompatible avec l'état de la ressource répond `409` : changer un à un les épisodes d'une playlist dynamique, mélanger une playlist qui n'est pas en ordre aléatoire. Les templates ne la proposent pas dans ce cas.

Quand le client doit réagir différemment selon la cause, `type` la nomme. Le SDK la garde (`ApiException.Rejected`) et la traduit pour l'utilisateur.

| `type` | Statut | Cause |
|---|---|---|
| `/problems/invalid-feed-url` | 422 | Adresse invalide ou autre schéma que `http(s)` |
| `/problems/forbidden-feed-address` | 422 | Réseau privé ou local (protection SSRF, ADR 0007) |
| `/problems/feed-unreachable` | 422 | Flux injoignable (réseau, statut HTTP, trop de redirections) |
| `/problems/feed-too-large` | 422 | Flux de plus de 20 Mo |
| `/problems/not-a-feed` | 422 | La réponse n'est pas un flux RSS |
| `/problems/not-an-opml` | 422 | Fichier OPML illisible |
| `/problems/search-failed` | 502 | Aucun annuaire n'a répondu |
| `/problems/audio-link-expired` | 403 | Lien audio signé expiré : relire l'épisode pour en obtenir un nouveau |
| `/problems/too-many-attempts` | 429 | Trop de mauvais mots de passe : `Retry-After` dit dans combien de secondes réessayer |

## Formats

- **Identifiants** : chaînes opaques. Ne pas les interpréter.
- **Dates** : ISO 8601 en UTC (`2026-10-12T06:00:00Z`).
- **Durées et positions** : en secondes (`durationSeconds`, `positionSeconds`).
- **Champs dérivés** (progression, minutes restantes) : non exposés, les clients les calculent. Le SDK le fait dans `Episode`.
- **Énumérations** : en majuscules (`NEW`, `IN_PROGRESS`, `PLAYED`).
- **OPML** : `text/x-opml`, par le lien `opml` de la collection des abonnements. `GET` exporte et `POST` importe (corps brut, ou formulaire `multipart` depuis le client léger). L'import répond par un bilan : importés, déjà suivis, refusés avec leur cause.
- **Audio** : le lien `audio` d'un épisode est **signé** (`?expires=…&signature=…`) et valable 24 heures, car les lecteurs (Media3, AVPlayer, JavaFX, `<audio>`) n'envoient pas de jeton. Il s'ouvre sans authentification et se donne tel quel au lecteur. Il sert le fichier téléchargé par le serveur (requêtes `Range` acceptées), sinon redirige vers l'enclosure de l'éditeur. Sans signature, une session ou un jeton donne aussi accès (ADR 0008).
- **Comptes** (ADR 0009) : `GET /me` (profil, templates `update-profile`, `change-password`, `logout`), `/me/devices` (templates `revoke`). `GET /users` est lisible par tous ; un administrateur y trouve `create-user`, et sur chaque compte `update-user`, `reset-password` et `delete-user`.
- **Partages** (ADR 0009) :
  - un abonnement porte `access` (`OWNER`, `WRITE`, `READ`) et `ownerName` quand il est partagé avec l'utilisateur ; ses templates suivent ces droits (`update`, `unsubscribe`, `share`, `subscribe`) ;
  - `share` (`userId`, `permission`, `message`) et `recommend` (`userId`, `message`) créent un partage ; le même destinataire une seconde fois remplace le droit et le message ;
  - `GET /shared` donne ce que les autres partagent avec l'utilisateur ; `/shares/{id}` porte `unshare` (propriétaire et destinataire) et `change-permission` (propriétaire, sauf une recommandation).
- **Favoris et notes** (ADR 0010) : un épisode porte `favorite` et `rating` (1 à 5, absent s'il n'est pas noté), propres à l'utilisateur, et les templates `favorite` ou `unfavorite`, `rate` (`rating`) et `clear-rating`.
- **Playlists** (ADR 0010) :
  - `GET /playlists` donne les playlists de l'utilisateur et porte `create-playlist` (`title`, `description`, `kind`, et pour une dynamique `keywords`, `favoritesOnly`, `minRating`, `unplayedOnly`, `order`, `limit`) ;
  - une playlist embarque ses épisodes dans l'ordre d'écoute, avec l'état de l'utilisateur, et porte `kind`, `rules` (dynamique), `episodeCount`, `access` et `ownerName` (partagée) ;
  - ses templates suivent les droits : `update-playlist`, `add-episode` (statique), `shuffle` (aléatoire), `share`, `publish-feed`, `unpublish-feed`, `delete-playlist` ; ses épisodes portent `remove-from-playlist` et `move-in-playlist` dans une statique modifiable.
- **Flux RSS de playlists** (ADR 0010) : le lien `feed` (`application/rss+xml`), visible du propriétaire une fois le flux publié, mène à `/feeds/{secret}`. Cette adresse et les enclosures `/feeds/{secret}/episodes/{id}` s'ouvrent sans authentification : le secret en tient lieu.
- **File d'attente** : `GET /queue` (lien `queue` de la racine) donne les épisodes dans l'ordre d'écoute. Chaque épisode indique `inQueue` et porte `enqueue` (hors de la file), ou `dequeue` et `move` (dans la file). `enqueue` envoie `episodeId` (pré-rempli, `readOnly`) et une `position` facultative : `0` pour « lire ensuite », absente pour la fin de la file.

## Évolution

- **Ajouts** : un champ, un lien ou un template peut être ajouté sans changer de version. Les clients ignorent ce qu'ils ne connaissent pas.
- **Ruptures** : supprimer ou renommer un élément est une rupture. On incrémente alors `apiVersion` à la racine.
- **Garde-fou** : les tests de contrat de `:server` comparent la spécification OpenAPI et les routes réelles, dans les deux sens, et valident chaque réponse contre son schéma.
