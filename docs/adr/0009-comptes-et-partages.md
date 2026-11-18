# ADR 0009 — Comptes et partages

- Statut : accepté
- Date : 17 novembre 2026
- Complète : ADR 0005 (authentification et droits) et ADR 0006 (comptes et multiserveur)

## Contexte

L'étape 6 rend le service vraiment multi-utilisateur :
- gérer les comptes (création, rôle, mot de passe, suppression) sans passer par la base ;
- changer son mot de passe, et déconnecter un appareil perdu ;
- partager abonnements, épisodes et playlists, en lecture ou en modification.

L'ADR 0005 a déjà fixé la table `shares`, la règle unique `can()` et le choix entre 404 et 403. Il restait à décider qui crée les comptes, ce que l'on partage exactement pour chaque type de ressource, et comment les clients le montrent.

## Décision

### Comptes

- **Premier administrateur** : `ADMIN_USERNAME` et `ADMIN_PASSWORD` le créent au démarrage, seulement si la base n'a encore aucun compte.
- **Pas d'inscription libre** : un administrateur crée les autres comptes (`POST /users`).
- **Annuaire** : `GET /users` est lisible par tous, pour choisir avec qui partager. La gestion (`create-user`, `update-user`, `reset-password`, `delete-user`) est réservée aux administrateurs ; les autres reçoivent 403.
- **Garde-fous** :
  - le dernier administrateur ne peut pas redevenir simple utilisateur ;
  - un administrateur ne supprime pas son propre compte ;
  - supprimer un compte supprime ses abonnements, ses états d'écoute et ses partages, ainsi que les podcasts que plus personne ne suit.
- **Règles partagées** : mot de passe de 8 caractères au moins ; identifiant de 3 à 32 caractères (`a-z`, `0-9`, `.`, `_`, `-`) ; nom affiché de 1 à 100 caractères ; message de partage de 500 caractères au plus. Elles sont dans `:shared` (`AccountRules`, `ShareRules`) : le serveur les applique, les clients les vérifient avant d'envoyer.
- **Changer son mot de passe** (`PUT /me/password`) :
  - l'appareil qui le change reste connecté, tous les autres jetons sont révoqués ;
  - les sessions du client léger expirent : `users.credentials_version` augmente à chaque nouveau mot de passe, et une session n'est valable que pour la version qu'elle porte.
- **Réinitialisation** par un administrateur : elle déconnecte le compte partout.
- **Appareils** : `GET /me/devices` liste les jetons actifs (nom, dernière utilisation, appareil courant) ; `DELETE /me/devices/{id}` en révoque un.
- **Tentatives** :
  - 5 échecs en 15 minutes, pour un même identifiant depuis une même adresse, bloquent les essais suivants : `429`, `Retry-After`, type `/problems/too-many-attempts` ;
  - cela vaut pour l'obtention d'un jeton, le formulaire du client léger et le changement de mot de passe ;
  - le compteur est en mémoire : le serveur tourne en une seule instance, et un redémarrage le remet à zéro.

### Partages

| Ressource | Droits possibles | Ce que `WRITE` permet en plus de lire |
|---|---|---|
| Abonnement | `READ`, `WRITE` | Changer la note et le téléchargement automatique |
| Épisode | `READ` seulement : une **recommandation**, avec un message | — |
| Playlist | `READ`, `WRITE` | Changer le titre, les règles et les épisodes, mélanger (étape 7, ADR 0010) |

- **Une seule table** `shares(resource_type, resource_id, owner_id, grantee_id, permission, message)`. Partager de nouveau avec la même personne remplace le droit et le message.
- **Pas de désabonnement ni de repartage** pour un destinataire, même en `WRITE` : `DELETE` et `SHARE` restent au propriétaire et aux administrateurs (`can()`, ADR 0005).
- **404 ou 403** : ce que l'on ne peut pas lire répond 404 ; ce que l'on lit sans avoir le droit de le faire répond 403.
- **Côté destinataire** :
  - `GET /shared` liste ce qu'on lui partage, chaque ressource embarquée telle qu'il la voit ;
  - un abonnement indique `access` (`OWNER`, `WRITE`, `READ`) et `ownerName`, et ses templates suivent ces droits : `update` en `WRITE`, `subscribe` pour le suivre soi-même ;
  - il n'apparaît ni dans ses abonnements ni dans ses nouveaux épisodes tant qu'il ne s'y abonne pas.
- **Fin d'un partage** :
  - le propriétaire le retire, ou le destinataire le refuse (`DELETE /shares/{id}`) ;
  - un partage disparaît avec ce qu'il partage : désabonnement, podcast supprimé, compte supprimé.
- **Administrateurs** : ils voient et modifient tout (`can()`), sans que cela crée de partage.
- **Liens** : la racine donne les gabarits `subscription` (`/subscriptions/{id}`) et `share` (`/shares/{id}`). Un abonnement partagé n'est pas dans la liste du destinataire : c'est par là qu'il l'atteint.

### Clients

- **SDK** :
  - `PodcastClient` gagne `AccountRepository` et `SharingRepository` ;
  - une action que le serveur ne propose pas échoue avec `ApiException.NotOffered` ;
  - les refus sont traduits pour l'utilisateur par statut et par champ (`errors`), et `TooManyAttempts` donne le délai.
- **Apps** :
  - Réglages : profil, mot de passe, appareils, et comptes du serveur pour un administrateur ;
  - Bibliothèque : « Partagés avec moi » ; page d'un podcast selon les droits ; partage et recommandation ;
  - la démo joue un petit serveur : l'utilisateur de démo l'administre, Alice lui partage un abonnement, Bob lui recommande un épisode.
- **Client léger** :
  - Réglages est la représentation HTML de `/me`, comme chaque écran est une ressource de l'API ;
  - un refus garde son statut, avec pour corps la phrase des apps dans la langue de l'utilisateur ; la page configure htmx pour insérer les réponses 403, 404, 422 et 429.
- **CLI** : `profile`, `passwd`, `devices`, `users`, `shared`, `share`, `unshare`, `recommend`.

## Conséquences

- La migration `V4` ajoute `shares` et `users.credentials_version`.
- L'étape 7 n'aura qu'à ajouter le type `PLAYLIST` aux routes de partage : la table, `can()` et l'affichage existent déjà.
- À plusieurs instances, le compteur de tentatives devrait passer en base.
- Hors périmètre : inscription libre, groupes, partage public par lien, OAuth/OIDC (ADR 0005).
