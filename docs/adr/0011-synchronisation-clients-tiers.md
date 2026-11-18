# ADR 0011 — Synchronisation avec les clients tiers

- Statut : accepté
- Date : 18 novembre 2026
- Étude : 2 (fiche `docs/etudes/02-synchronisation-clients-tiers.md`), POC `poc/gpodder-sync/`
- Complète : ADR 0005 (authentification), ADR 0007 (abonnements), ADR 0008 (position d'écoute)

## Contexte

Un utilisateur doit pouvoir écouter sur AntennaPod (ou un autre client gPodder) et retrouver ses abonnements et sa position sur nos clients, et inversement. Le sujet impose aussi l'**Open Podcast API**, dont la spécification ne couvre encore que les abonnements.

L'étude a branché un vrai **AntennaPod 3.12.3** (émulateur Android) sur un serveur gPodder minimal qui journalise chaque requête (`poc/gpodder-sync/`). Le scénario de la fiche a été joué en entier : abonnement dans AntennaPod, écoute puis pause, position modifiée côté serveur et reprise par AntennaPod, épisode marqué écouté, désabonnement. Les traces sont dans `poc/gpodder-sync/traces/antennapod-3.12.3.log`.

## Réponses de l'étude

1. **Sous-ensemble gPodder v2 utilisé par AntennaPod** (rien d'autre n'a été demandé) :

   | Requête | Quand |
   |---|---|
   | `POST /api/2/auth/{user}/login.json` | Configuration, puis **au début de chaque synchronisation** |
   | `GET /api/2/devices/{user}.json` | Configuration : choix d'un appareil existant |
   | `POST /api/2/devices/{user}/{device}.json` | Configuration : `{"caption": "AntennaPod on …", "type": "mobile"}` |
   | `GET /api/2/subscriptions/{user}/{device}.json?since=` | Chaque synchronisation (`since=0` la première fois) |
   | `POST /api/2/subscriptions/{user}/{device}.json` | Après un abonnement ou un désabonnement : `{"add": [...], "remove": [...]}` |
   | `GET /api/2/episodes/{user}.json?since=` | Chaque synchronisation |
   | `POST /api/2/episodes/{user}.json` | Après une pause ou « marquer comme écouté » |

2. **Authentification** : `Basic` sur `login.json` seulement. Le serveur pose le cookie `sessionid`, que les requêtes suivantes portent seul. AntennaPod se reconnecte à chaque synchronisation, cookie compris. Il accepte un serveur en **`http://`** avec l'avertissement « Password and data are not encrypted! » : utile en développement, HTTPS reste obligatoire en production (ADR 0005).
3. **Mots de passe d'application** : AntennaPod envoie ce que l'utilisateur saisit comme mot de passe. Un jeton d'appareil accepté en `Basic` (ADR 0005) convient donc tel quel.
4. **Open Podcast API** : aucun client répandu ne l'utilise encore. On implémente ses points d'accès d'abonnements, vérifiés par nos tests.
5. **Correspondance des épisodes** : une action porte `podcast` (URL du flux), `episode` (URL du fichier audio, paramètres compris, par exemple `…/128_default_tc.mp3?aid=rss_feed&feed=…`) et `guid` (celui du flux). AntennaPod retrouve un épisode par `guid`, sinon par l'URL du fichier.
6. **Conflits** : AntennaPod applique une action reçue sauf s'il a lui-même une action plus récente en attente. Le serveur fait de même avec l'horodatage de l'action (voir plus bas).

Autres observations :
- une écoute donne `"action": "play", "started": 0, "position": 37, "total": 3642` ;
- « Marquer comme écouté » donne `started = position = total` (la durée) ;
- l'horodatage est en UTC sans fuseau : `"2026-11-18T08:19:12"`.

## Décision

### API gPodder (sous-ensemble ci-dessus, plus `logout.json`)

- **Authentification** :
  - `Basic` accepte le mot de passe du compte ou un **mot de passe d'application**, c'est-à-dire un jeton d'appareil ;
  - les échecs comptent dans la limite de tentatives (ADR 0009) ;
  - `login.json` pose un cookie `sessionid` (chemin `/api/2/`, `HttpOnly`) : une session en base, distincte de celle du client léger ;
  - une reconnexion qui porte déjà une session valide la garde, sans en créer de nouvelle à chaque synchronisation ;
  - la session tombe quand le mot de passe change ou quand le mot de passe d'application est révoqué ;
  - l'utilisateur du chemin doit être celui qui s'est authentifié, sinon `401`.
- **Appareils** : la table `gpodder_devices` garde l'identifiant choisi par le client, son nom et son type. Nos abonnements appartiennent à l'utilisateur, pas à l'appareil : tous ses appareils voient la même liste, comme un groupe de synchronisation gPodder qui les contiendrait tous.
- **Abonnements** :
  - le journal `subscription_changes` garde, pour chaque utilisateur et chaque URL de flux, le dernier état (abonné ou non) et sa date ;
  - il est tenu par tous les chemins : nos clients, l'OPML, gPodder et l'Open Podcast API ;
  - `GET … ?since=0` donne tous les abonnements actuels ; `since=t` donne ce qui a changé depuis `t` ;
  - `POST` : un ajout passe par le même chemin que l'import OPML (podcast en attente, lu en arrière-plan, protection SSRF) ; un retrait désabonne ; la même URL dans `add` et `remove` donne `400` ;
  - `update_urls` renvoie `""` pour une URL qui n'est pas en `http(s)`.
- **Actions sur les épisodes reçues** :
  - l'épisode est cherché dans le podcast de l'URL `podcast` (URL normalisée), par `guid` puis par URL du fichier audio ;
  - `play` : position ; l'épisode est écouté quand `position ≥ total − 5 s` ;
  - `new` : non écouté ;
  - `download` et `delete` concernent les fichiers de l'appareil et n'ont pas d'effet ;
  - **règle des conflits : la date la plus récente gagne.** Une action plus ancienne que le dernier changement de l'état (`playback_states.updated_at`) est ignorée ; une action sans date prend celle de sa réception ;
  - **épisode inconnu** (flux pas encore lu, par exemple juste après un ajout) : l'action est gardée dans `episode_actions`, puis appliquée après la lecture suivante du flux, avec la même règle.
- **Actions sur les épisodes envoyées** (`GET … ?since=t`) :
  - le serveur ne garde pas d'historique d'actions : il décrit l'**état actuel** de chaque épisode changé depuis `t`, sous la forme d'une action `play` ;
  - épisode écouté : `started = position = total = durée` ;
  - épisode en cours : `started = 0`, `position`, `total = durée` ;
  - s'y ajoutent les actions reçues et pas encore appliquées, pour les autres appareils gPodder ;
  - un épisode remis à « non écouté » n'est pas envoyé : AntennaPod ignore une position nulle.
- **Horodatages** : en secondes Unix. `since` est inclusif : une action peut revenir deux fois, ce qui est sans effet.

### Open Podcast API (v1, abonnements)

- `GET /v1/subscriptions` (`since`, `page`, `per_page`), `POST /v1/subscriptions`, `GET`, `PATCH` et `DELETE /v1/subscriptions/{guid}`, `GET /v1/deletions/{id}`, au format JSON de la spécification.
- **GUID d'un abonnement** : celui que le client envoie, sinon celui de l'espace de noms Podcast (`podcast:guid`), c'est-à-dire un UUIDv5 de l'URL du flux sans protocole.
- **Suppression** : désabonne, marque l'entrée supprimée (`deleted`) et répond `202` avec un `deletion_id`. La suppression est faite tout de suite, donc son état vaut `SUCCESS`. L'entrée répond ensuite `410`.
- **Authentification** : la spécification n'en décrit pas encore. On accepte un jeton (`Bearer`) ou `Basic` comme pour gPodder.

### Mots de passe d'application

- Créés dans les Réglages de chaque client (« Appareils ») avec un nom, par exemple « AntennaPod ».
- Le serveur émet un jeton d'appareil, montré **une seule fois**.
- Il apparaît ensuite parmi les appareils, avec sa dernière utilisation, et se révoque comme eux.

## Conséquences

- La migration `V7` ajoute `gpodder_devices`, `subscription_changes` (rempli à partir des abonnements existants) et `episode_actions`.
- Les routes gPodder et Open Podcast API figurent dans la spécification OpenAPI, sous leurs propres étiquettes. Elles ne sont pas hypermédia : leurs formats sont imposés par les clients existants.
- **Non synchronisé**, faute d'équivalent dans ces API : la file d'attente, les favoris, les notes, les playlists, et le retour d'un épisode à « non écouté ».
- Une playlist reste accessible aux clients tiers par son flux RSS (ADR 0010), l'ensemble des abonnements par l'OPML (ADR 0007).
- Hors périmètre : abonnements propres à chaque appareil, annuaire et recherche de gpodder.net, extension « Episodes » de l'Open Podcast API quand elle sera publiée.
