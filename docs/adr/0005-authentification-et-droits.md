# ADR 0005 — Authentification et droits

- Statut : accepté
- Date : 13 octobre 2026
- Étude : n° 6 du plan (étape 2) — POC dans `poc/auth/`

## Contexte et contraintes

- **Client léger** (HTML + htmx) : navigateur, formulaires, pas de JavaScript applicatif.
- **Clients riches** et **mobile multiserveur** : un même appareil peut être connecté à plusieurs serveurs, avec un compte sur chacun.
- **Interopérabilité** :
  - L'API gPodder authentifie en **HTTP Basic** (`POST /api/2/auth/{user}/login.json`) puis par cookie `sessionid`.
  - L'Open Podcast API ne spécifie pas encore d'authentification : toutes ses specs sont « in progress ».
  - Les flux RSS de playlists (étape 7) seront lus par des applications tierces qui ne savent pas se connecter.
- **Droits** : partage d'épisodes, de playlists et d'abonnements, en lecture ou en modification. Fichiers mutualisés, état d'écoute par utilisateur (ADR 0004).

## Options étudiées

| Besoin | Options | Retenu |
|---|---|---|
| Stockage des mots de passe | bcrypt, scrypt, **Argon2id** | Argon2id (recommandation OWASP), via password4j |
| Client léger | Jeton en `localStorage`, **session serveur + cookie** | Cookie `HttpOnly`, `Secure`, `SameSite=Lax`, session stockée en base |
| Clients riches | JWT, **jeton opaque** | Jeton opaque de 256 bits, un par appareil, dont seul le SHA-256 est stocké |
| Clients gPodder | Mot de passe réel en Basic, **mot de passe d'application** | Basic accepte le mot de passe *ou* un jeton d'appareil |

**Pourquoi pas JWT ?**
- Un JWT ne se révoque pas sans une liste noire, c'est-à-dire sans retrouver un état côté serveur.
- Nous n'avons qu'un service, donc rien à gagner à l'absence d'état.
- Chaque serveur émet ses propres jetons, et le multiserveur n'impose pas de format partagé.
- Un jeton opaque haché en base se révoque appareil par appareil (téléphone perdu) et peut enregistrer sa dernière utilisation.

## Résultats du POC

| Scénario | Résultat |
|---|---|
| Hash stocké au format `$argon2id$…`, mauvais mot de passe refusé | ✅ |
| Connexion par formulaire, puis accès par cookie de session | ✅ |
| Jeton obtenu une fois (Basic sur `POST /api/tokens`), puis Bearer ; refusé (401) après révocation | ✅ |
| Connexion gPodder avec un mot de passe d'application : cookie `sessionid` posé ; le jeton d'un autre utilisateur est refusé | ✅ |
| Playlist non partagée : 404 ; partage READ : lecture OK, modification 403 ; partage WRITE : modification OK, suppression 403 ; après retrait du partage : 404 | ✅ |

## Décision

**Authentification** : un seul type de principal (`User`), trois façons de l'obtenir :

| Mécanisme | Utilisé par | Détails |
|---|---|---|
| Session (cookie) | Client léger | `SameSite=Lax` bloque les envois de formulaires inter-sites ; les requêtes htmx qui modifient l'état exigent en plus l'en-tête `HX-Request` |
| Bearer (jeton opaque) | Clients riches, mobile, CLI | Obtenu par `POST /api/tokens` (Basic, une seule fois). L'appareil ne stocke jamais le mot de passe. |
| Basic | API gPodder (et Open Podcast API en attendant sa spec) | Mot de passe *ou* mot de passe d'application ; on recommande le second dans l'interface |

**Multiserveur** : `:shared` gère une liste de comptes `Account(serverUrl, username, token)`. Chaque serveur est indépendant.

**Flux RSS de playlists** : URL à capacité (`/feeds/{secret}.xml`), un secret aléatoire par flux, révocable et régénérable.

**Droits** :
- Chaque ressource partageable (abonnement, playlist, épisode) a un **propriétaire**.
- Une table `shares(resource_type, resource_id, grantee_id, permission)` donne `READ` ou `WRITE`.
- Une seule fonction `can(user, action, resource)`, appelée par tous les endpoints :

| | READ | MODIFY | DELETE / SHARE |
|---|---|---|---|
| Propriétaire, admin | ✅ | ✅ | ✅ |
| Partage `WRITE` | ✅ | ✅ | ❌ |
| Partage `READ` | ✅ | ❌ | ❌ |
| Autres | ❌ (404) | ❌ (404) | ❌ (404) |

- **404 et non 403** pour ce que l'on ne peut pas lire : on ne révèle pas l'existence des identifiants des autres.
- **Rôles** : `admin` (gestion des comptes et réglages du serveur, comme la purge) et `user`. Pas de groupes pour l'instant.
- **Hypermédia** (ADR 0003) : les templates HAL-FORMS ne sont proposés que si `can()` l'autorise. Le client n'affiche donc pas « Modifier » sur une playlist partagée en lecture.

## Conséquences

- **Étape 3** : tables `users`, `sessions`, `tokens`, `shares`, et `owner_id` sur les ressources partageables.
- **Étude 2** : vérifier avec un vrai AntennaPod qu'il accepte un mot de passe d'application et s'il renvoie `sessionid` ou Basic à chaque requête.
- **Sécurité** : HTTPS obligatoire en production (cookies `Secure`, Basic en clair sinon) et limitation des tentatives de connexion, à prévoir avec les comptes (étape 6).
- **Plus tard** : groupes, partage public par lien et OAuth/OIDC sont hors périmètre ; le modèle `shares` pourra les accueillir (`grantee` de type groupe ou lien).
