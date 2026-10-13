# Fiche d'étude 2 — Synchronisation avec les clients tiers

**Objectif** : qu'un utilisateur puisse écouter sur AntennaPod (ou Kasts, gPodder) et retrouver ses abonnements et sa position sur nos clients, et inversement. Prérequis de l'étape 8.

## Questions à trancher

1. Quel **sous-ensemble de l'API gPodder v2** faut-il implémenter pour qu'AntennaPod synchronise abonnements *et* positions ?
2. Comment AntennaPod s'authentifie-t-il ? Login Basic puis cookie `sessionid`, ou Basic à chaque requête ? Accepte-t-il un serveur en HTTP (sans TLS) pour le développement ?
3. Les **mots de passe d'application** (jetons d'appareil acceptés en Basic, ADR 0005) fonctionnent-ils avec AntennaPod ?
4. Quelle place pour l'**Open Podcast API** ? Sa spec cœur ne couvre que les abonnements et tout est encore « in progress ».
5. Comment faire correspondre un épisode gPodder (identifié par URL de podcast + URL de l'audio) avec nos épisodes (identifiant interne) ?
6. Comment résoudre les conflits de position quand deux appareils écrivent ? (Dernier horodatage gagnant ? Position la plus avancée ?)

## Options et pistes

- **gPodder** : endpoints probables `auth/{user}/login.json`, `devices/{user}.json`, `subscriptions/{user}/{device}.json` (GET/POST avec `since`), `episodes/{user}.json` (actions `play` avec `started`/`position`/`total`). Le vérifier sur la doc officielle et dans le code source d'AntennaPod (`GpodnetService`).
- **Implémentations existantes à lire** : le serveur gPodder officiel (mygpo), le module gpoddersync de Nextcloud, oPodSync. Ce sont des sources d'inspiration ; on ne réutilise pas leur code.
- **Open Podcast API** : implémenter seulement les endpoints d'abonnements et suivre l'évolution de la spec.

## Critères

Couverture fonctionnelle avec AntennaPod (abonnements, position, statut écouté), effort d'implémentation, robustesse face aux conflits, compatibilité avec le modèle de l'ADR 0004 (fichiers mutualisés, état par utilisateur).

## POC suggéré (`poc/gpodder-sync/`)

- Un serveur Ktor minimal en mémoire, avec les 4 ou 5 endpoints gPodder et des logs de chaque requête reçue.
- AntennaPod (F-Droid ou Play Store) sur l'émulateur Android ; depuis l'émulateur, la machine hôte est `http://10.0.2.2:8080`.
- Scénario :
  1. s'abonner dans AntennaPod et vérifier la synchronisation ;
  2. écouter 2 minutes et vérifier que la position arrive ;
  3. modifier la position côté serveur et vérifier qu'AntennaPod la récupère.
- Garder les traces des requêtes : ce sont la meilleure documentation du sous-ensemble réellement utilisé.

## Livrable

ADR « Synchronisation avec les clients tiers », qui doit contenir :
- la liste exacte des endpoints à implémenter à l'étape 8 ;
- la règle de résolution des conflits ;
- la correspondance des identifiants ;
- la réponse aux questions d'authentification ci-dessus.
