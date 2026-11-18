# ADR 0010 — Playlists, favoris et notes

- Statut : accepté
- Date : 18 novembre 2026
- Complète : ADR 0005 (droits), ADR 0007 (purge), ADR 0008 (lecture) et ADR 0009 (partages)

## Contexte

L'étape 7 demande des playlists :
- **statiques** : l'utilisateur choisit les épisodes et leur ordre ;
- **dynamiques** : par mots-clés, par favoris ou notes, en ordre aléatoire ;
- **partagées** avec d'autres utilisateurs, en lecture ou en modification ;
- exposées chacune en **flux RSS**, dont les enclosures pointent vers les fichiers hébergés.

Une playlist dynamique « favoris / notes » suppose que les favoris et les notes existent. Le plan les plaçait à l'étape 9 : ils arrivent dès cette étape-ci. Il reste à l'étape 9 la recherche multicritère, les tags et les statistiques.

L'ADR 0005 a fixé le principe du flux (URL à capacité). L'ADR 0007 a prévu que les épisodes en playlist échappent à la purge.

## Décision

### Favoris et notes

- **Par utilisateur**, avec le reste de son état d'un épisode : `playback_states.favorite` et `playback_states.rating` (1 à 5, absente si l'épisode n'est pas noté).
- **Actions d'un épisode** : `favorite` / `unfavorite`, `rate` (`rating` de 1 à 5) et `clear-rating`. Elles s'appliquent à tout épisode lisible, comme `mark-played`.
- **Affichage** : un épisode porte `favorite` et `rating` dans toutes ses représentations (Accueil, file d'attente, podcast, playlist).

### Deux sortes de playlists

| | Statique | Dynamique |
|---|---|---|
| Épisodes | Choisis un à un, dans l'ordre voulu | Calculés à chaque lecture à partir des règles |
| Source | N'importe quel épisode du catalogue du serveur | Les abonnements du **propriétaire** |
| Modifier les épisodes | `add-episode`, `move-in-playlist`, `remove-from-playlist` | Impossible : `409` |

- **La sorte ne change pas** après la création. Changer d'avis revient à créer une autre playlist.
- **Règles d'une playlist dynamique** (`PlaylistRules`, dans `:shared`) :
  - `keywords` : au plus 10 mots-clés de 50 caractères. Un épisode est retenu si l'un d'eux figure, sans tenir compte de la casse, dans son titre, sa description ou le titre de son podcast ;
  - `favoritesOnly`, `minRating` (0 = toutes), `unplayedOnly` ;
  - `order` : `NEWEST`, `OLDEST` ou `RANDOM` ;
  - `limit` : de 1 à 100 épisodes, 50 par défaut.
- **Calcul sur le compte du propriétaire** : les favoris, les notes et le statut « écouté » qui filtrent sont les siens. Un destinataire voit donc la même liste que lui. Chaque épisode s'affiche avec l'état du lecteur : sa position, ses favoris, ses notes.
- **Aléatoire stable** : l'ordre `RANDOM` dépend d'une graine (`shuffle_seed`). La liste ne bouge pas d'une lecture à l'autre, ce qui permet de la reprendre. L'action `shuffle` tire une nouvelle graine. Elle n'existe que pour une playlist aléatoire, sinon `409`.
- **Limites communes** : titre de 1 à 100 caractères, description de 500 au plus (`PlaylistLimits`). Le serveur les applique, les clients les vérifient avant d'envoyer.

### Droits et partage

- **Même mécanisme qu'à l'ADR 0009** : table `shares` avec le type `PLAYLIST`, règle `can()`, 404 pour ce qui n'est pas lisible, 403 pour ce qui est lisible mais interdit.

| Action | Propriétaire et administrateurs | Partage `WRITE` | Partage `READ` |
|---|---|---|---|
| Lire, tout lire | ✓ | ✓ | ✓ |
| Changer titre, description, règles ; épisodes d'une statique ; mélanger | ✓ | ✓ | 403 |
| Partager, publier le flux, supprimer | ✓ | 403 | 403 |

- **Côté destinataire** : la playlist apparaît dans `GET /shared` et dans la liste « Partagées avec moi », avec `access` et `ownerName`. Elle n'est pas dans `GET /playlists`, qui ne liste que les siennes.
- **Ajouter à une playlist partagée** : un destinataire `WRITE` y ajoute un épisode du catalogue, que les autres lecteurs voient alors même s'ils ne suivent pas ce podcast. Le catalogue est mutualisé et lisible par tout compte (`docs/api/modele-de-donnees.md`).

### Flux RSS

- **Publication par le propriétaire** : `PUT /playlists/{id}/feed` crée une adresse secrète, `/feeds/{secret}` (24 octets aléatoires en base64url, sans extension `.xml` contrairement à ce qu'annonçait l'ADR 0005). Publier de nouveau change l'adresse, et l'ancienne ne répond plus. `DELETE` retire le flux.
- **Lien `feed`** : seuls le propriétaire et les administrateurs le voient sur la playlist, sous forme d'adresse relative que le SDK rend absolue.
- **Contenu** : RSS 2.0 avec les balises iTunes. Le titre et la description sont ceux de la playlist, l'auteur est son propriétaire. Chaque épisode garde le `guid` de son flux d'origine, pour qu'une appli de podcasts ne le compte pas deux fois. Une playlist dynamique y est calculée comme pour son propriétaire.
- **Enclosures** : `/feeds/{secret}/episodes/{id}`, qui sert le fichier hébergé (requêtes `Range` acceptées) ou redirige vers l'éditeur, comme le lien `audio` (ADR 0008). Ce ne sont pas les liens signés de 24 heures : une appli de podcasts télécharge parfois un épisode plusieurs jours après avoir lu le flux. L'adresse ne sert que les épisodes présents dans la playlist, et cesse avec le flux.

### Purge

- **Exception** : un épisode présent dans une playlist statique n'est jamais purgé (`purgeCandidates` exclut `playlist_items`), comme un épisode gardé ou en file d'attente (ADR 0007).
- **Pas de protection pour les dynamiques** : leurs épisodes changent sans cesse et peuvent être nombreux. Ils restent lisibles par redirection vers l'éditeur.

### API

- **Racine** : lien `playlists` et gabarit `playlist` (`/playlists/{id}`).
- **Collection** : `GET /playlists`, template `create-playlist`.
- **Playlist** : `GET`, `PATCH` et `DELETE /playlists/{id}`. Ses templates suivent les droits : `update-playlist`, `add-episode` (statique), `shuffle` (aléatoire), `share`, `publish-feed`, `unpublish-feed`, `delete-playlist`. Ses épisodes embarqués portent `remove-from-playlist` et `move-in-playlist` (statique, pour qui peut la modifier).
- **Partages** : `/playlists/{id}/shares`, sur le modèle des abonnements.

### Clients

- **SDK** : `PlaylistRepository` (`playlists`, `playlist`, `createPlaylist`, `updatePlaylist`, `addToPlaylist`…) ; `setFavorite` et `rate` dans `LibraryRepository` ; `sharePlaylist` et `sharesOfPlaylist` dans `SharingRepository`. La démo contient deux playlists à l'utilisateur et une que Bob lui partage.
- **Lecture** : « Tout lire » joue le premier épisode et place les suivants en tête de la file d'attente (`PlaybackController.playAll`). La file reste la seule liste d'écoute : pas de second mécanisme à synchroniser.
- **Apps** : destination Playlists (liste et playlist côte à côte en fenêtre large), création et modification dans une boîte de dialogue, ajout à une playlist depuis un épisode, cœur et étoiles sur chaque épisode et dans le lecteur.
- **Client léger** : pages `/playlists` et `/playlists/{id}` (représentations HTML de l'API) ; ajout à une playlist et note depuis l'Accueil. Après une création, une modification ou une suppression, htmx change de page par `HX-Location`, qui garde le lecteur.
- **CLI** : `playlists` (et `show`, `create`, `edit`, `add`, `remove`, `move`, `delete`, `play`, `shuffle`, `share`, `unshare`, `feed`), `favorite`, `rate`.

## Conséquences

- Les migrations `V5` (favoris et notes) et `V6` (`playlists`, `playlist_items`, `playlist_feeds`) s'ajoutent. Les règles sont des colonnes typées plutôt qu'un `jsonb`, ce qui permet à PostgreSQL de les vérifier (`CHECK`).
- Une playlist dynamique est recalculée à chaque lecture. Avec 100 épisodes au plus et les index existants, c'est négligeable à l'échelle du projet. Un cache ne s'imposerait qu'avec de gros catalogues.
- L'étape 8 pourra s'appuyer sur ces flux : un client tiers (AntennaPod) s'abonne à une playlist comme à n'importe quel podcast.
- Hors périmètre : dossiers de playlists, playlists publiques sans adresse secrète, règles combinées par « et » / « ou » au choix, tags (étape 9).
