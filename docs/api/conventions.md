# Conventions de l'API hypermédia

Règles communes à toutes les ressources du service. Elles appliquent les ADR 0003 (HAL + HAL-FORMS) et 0005 (authentification). Le détail de chaque ressource est dans la spécification OpenAPI : `server/src/main/resources/openapi/podcast-api.yaml`, consultable sur http://localhost:8080/docs quand le serveur tourne.

## Une URL, plusieurs représentations

Chaque ressource a **une seule URL** et le format est négocié avec l'en-tête `Accept` :

| `Accept` | Représentation | Pour qui |
|---|---|---|
| `application/prs.hal-forms+json` | HAL + `_templates` (actions possibles dans l'état courant) | SDK `:shared` (Compose, CLI) |
| `application/hal+json` ou `application/json` | HAL, en lecture seule | Outils et clients tiers |
| `text/html` | Page ou fragment HTML (client léger) | Navigateur |

Les réponses portent `Vary: Accept, Accept-Language`. Le JSON est **neutre en langue** : seul le HTML est traduit (ADR 0002).

Pour l'instant, seule la racine `/` a une représentation HTML. Les autres pages du client léger arrivent avec leurs fonctionnalités (étape 4 et suivantes).

## Point d'entrée et navigation

- **Racine** : un client ne connaît que l'URL du serveur. Il lit la racine `GET /` et suit les liens. Il ne construit jamais une URL lui-même, à l'exception des liens à gabarit.
- **Racine sans authentification** : nom du serveur, version de l'API, lien `tokens` et template `create-token` (comment se connecter).
- **Racine authentifiée** : liens `subscriptions`, `episodes`, `me`, et l'état de l'Accueil embarqué (`resume`, `latest`).
- **Liens** : `_links` contient toujours `self`. Les noms de relation sont en minuscules, avec un tiret si besoin.
- **Liens à gabarit** : ils suivent la RFC 6570 et portent `"templated": true`, par exemple `/episodes{?status,offset,limit}`.
- **Ressources embarquées** : `_embedded`. Un épisode embarque son podcast, pour qu'un client puisse l'afficher sans autre requête.

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

## Erreurs

Le format des erreurs est **Problem Details** (RFC 9457), en `application/problem+json` :

```json
{ "type": "about:blank", "title": "Not Found", "status": 404, "detail": "Episode 42 not found" }
```

Les erreurs de validation (`422`) ajoutent `errors: [{ "field": "positionSeconds", "message": "…" }]`.

## Formats

- **Identifiants** : chaînes opaques. Ne pas les interpréter.
- **Dates** : ISO 8601 en UTC (`2026-10-12T06:00:00Z`).
- **Durées et positions** : en secondes (`durationSeconds`, `positionSeconds`).
- **Champs dérivés** (progression, minutes restantes) : non exposés, les clients les calculent. Le SDK le fait dans `Episode`.
- **Énumérations** : en majuscules (`NEW`, `IN_PROGRESS`, `PLAYED`).

## Évolution

- **Ajouts** : un champ, un lien ou un template peut être ajouté sans changer de version. Les clients ignorent ce qu'ils ne connaissent pas.
- **Ruptures** : supprimer ou renommer un élément est une rupture. On incrémente alors `apiVersion` à la racine.
- **Garde-fou** : les tests de contrat de `:server` comparent la spécification OpenAPI et les routes réelles, dans les deux sens, et valident chaque réponse contre son schéma.
