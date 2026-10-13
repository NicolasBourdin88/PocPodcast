# ADR 0003 — Format hypermédia : HAL + HAL-FORMS

- Statut : accepté
- Date : 13 octobre 2026
- Étude : n° 1 du plan (étape 2) — POC dans `poc/hypermedia/`

## Question

Le sujet impose une **API hypermédia**. Le POC de l'étape 1 utilise HAL, qui ne décrit que des *liens*. La question clé : le format peut-il aussi décrire des **actions** (méthode, cible, paramètres), pour qu'un client n'ait pas à coder en dur « marquer comme écouté = `POST /episodes/{id}/played` » ?

## Options

| | HAL (+ HAL-FORMS) | Siren | JSON:API 1.1 |
|---|---|---|---|
| Liens | `_links` | `links` (rel en tableau) | `links` (self, related) |
| Actions | `_templates` (HAL-FORMS) : méthode, cible, type de contenu, propriétés | `actions` : méthode, href, type, champs | Aucune : CRUD implicite (`PATCH` sur `self`) |
| Paramètres | `required`, `min`/`max`, `regex`, `readOnly`, `type`, valeur par défaut | `type` HTML5, valeur par défaut | — |
| Valeurs possibles | `options` (inline ou par lien) | Aucune | — |
| Statut | HAL : brouillon IETF stable. HAL-FORMS : *working draft*, actif (dernier commit en sept. 2025), implémenté par Spring HATEOAS | Spec « work in progress », plus de commit depuis oct. 2020 | Spec 1.1 finale, très active |
| Déjà utilisé dans le POC | oui (HAL) | non | non |

## Critères et résultats du POC

Le POC expose le même épisode dans les trois formats, avec trois actions : mettre à jour la position, marquer comme écouté (proposée seulement si l'épisode n'est pas écouté) et ajouter à une playlist. Un client générique ne connaît que l'URL de l'épisode et le *nom* de l'action.

| Format | Taille (octets) | Actions découvrables | Actions conditionnelles | Choix (options) | Validation |
|---|---|---|---|---|---|
| HAL-FORMS | 823 | oui | oui | oui | oui |
| Siren | 611 | oui | oui | non | non |
| JSON:API | 148 | non | non | non | non |

- **JSON:API** échoue sur le critère clé : le client doit savoir à l'avance qu'il faut un `PATCH` sur `self` avec `{"data":{"attributes":…}}` (test `jsonApiNeedsOutOfBandKnowledge`). C'est un bon format de *données*, pas un format d'*actions*.
- **Siren** décrit les actions, mais pas les valeurs possibles. Pour « ajouter à une playlist », le client doit aller chercher les playlists lui-même, et la règle « noms de champs uniques » interdit de contourner le problème. La spec n'évolue plus.
- **HAL-FORMS** couvre tout. Les documents sont plus lourds (environ +35 % face à Siren), ce qui reste négligeable à cette échelle.

## Décision

- **HAL pour les données et les liens, HAL-FORMS pour les actions.**
  - `application/hal+json` : représentation en lecture seule, déjà en place et lisible par des outils tiers (HAL Explorer…).
  - `application/prs.hal-forms+json` : même document, avec `_templates` pour les actions disponibles dans l'état courant. C'est ce que demande le SDK `:shared`.
- Les templates sont **nommés** (`update-position`, `mark-played`…), comme dans Spring HATEOAS. La spec ne prédéfinit que la clé `default` mais autorise les autres ; notre client s'appuie sur les noms.
- Seul le sous-ensemble suivant est utilisé : `method`, `target`, `contentType` (toujours `application/json`), `properties` (`name`, `required`, `readOnly`, `value`, `type`, `min`, `max`, `regex`, `options.inline`, `options.link`).
- **Un seul modèle d'actions côté serveur** (`Affordance` : nom, méthode, cible, champs) et deux rendus :
  - en HAL-FORMS pour les clients riches ;
  - en `<form>` ou attributs htmx pour le client léger.

  Le client léger hérite ainsi des mêmes actions conditionnelles.
- Le SDK `:shared` aura un petit client générique HAL-FORMS, sans bibliothèque externe : il n'en existe pas de maintenue en Kotlin Multiplatform.

## Conséquences

- **Brouillon** : HAL-FORMS reste un brouillon. Le risque est faible, car nous contrôlons serveur et clients et n'utilisons qu'un sous-ensemble stable depuis des années. Si la spec bouge, l'adaptation se fait à un seul endroit de chaque côté.
- **Interopérabilité** : les API d'interopérabilité (gPodder, Open Podcast API) gardent leur format propre et ne sont pas concernées.
- **Contrat** : le contrat de l'étape 3 décrit chaque ressource avec ses liens *et* ses templates nommés. L'OpenAPI documente les endpoints cibles.
