# Scénario de la démo finale

Une vingtaine de minutes, du serveur aux clients tiers. Les commandes de lancement sont dans le [README](../README.md) ; les données de démo et les comptes, dans `server/src/dev/kotlin/fr/podcast/server/dev/DevSeed.kt`.

## Avant la démo

- Deux serveurs : `./gradlew :server:run` (port 8080) et `PORT=8081 SERVER_NAME="Serveur B" ./gradlew :server:run`.
- Les clients ouverts et connectés :
  - client léger dans le navigateur (`http://localhost:8080/dev/login/alice`) ;
  - app Android sur l'émulateur ;
  - app SwiftUI sur le simulateur iPhone et sur le Mac ;
  - app Compose desktop ;
  - terminal avec le CLI.
- AntennaPod sur l'émulateur, synchronisé avec Alice (mot de passe d'application) ; newsboat dans un terminal.

## 1. Le socle (2 min)

- L'architecture tient sur un schéma : un service Ktor sur PostgreSQL, une API hypermédia (HAL et HAL-FORMS, HTML pour le client léger) et un SDK Kotlin Multiplatform partagé par tous les clients ([ADR 0003](adr/0003-format-hypermedia.md), [ADR 0006](adr/0006-comptes-et-multiserveur.md)).
- La documentation de l'API est servie par le serveur (`/docs`). Les clients ne construisent aucune URL : ils suivent les liens de la racine.

## 2. S'abonner et écouter (4 min)

- **Android** : Explorer, rechercher « kotlin », s'abonner. Montrer Material 3 Expressive : barre de recherche, couleurs du fond d'écran, barre de navigation courte, bouton de lecture qui se morphe et ligne qui ondule ([ADR 0013](adr/0013-composants-natifs.md)).
- Lancer un épisode, le mettre en pause : la position part au serveur.
- **iPhone (SwiftUI)** : l'épisode reprend là où il en était. Montrer Liquid Glass :
  - l'onglet de recherche à part ;
  - le mini-lecteur au-dessus de la barre d'onglets ;
  - le lecteur plein écran et ses boutons en verre ;
  - le Centre de contrôle et l'écran verrouillé.
- **Client léger** : la même file d'attente, le lecteur intégré qui continue d'une page à l'autre.

## 3. Organiser (4 min)

- Étiquettes sur deux abonnements (« tech », « trajets »), puis filtre de la Bibliothèque ; recherche d'épisodes par mots, statut, note et période ([ADR 0012](adr/0012-recherche-etiquettes-statistiques.md)).
- Favoris et notes, puis une playlist dynamique « Kotlin non écouté » et son flux RSS à adresse secrète ([ADR 0010](adr/0010-playlists.md)).
- OPML : export depuis le Mac (menu de la Bibliothèque), import dans newsboat.

## 4. Plusieurs utilisateurs (4 min)

- Alice partage un abonnement avec Bob en lecture seule, puis en modification ; Bob recommande un épisode à Alice ([ADR 0009](adr/0009-comptes-et-partages.md)).
- Fichiers mutualisés, état d'écoute propre à chacun : le même épisode, deux positions.
- Avatars : Bob choisit le sien dans la grille, il apparaît chez Alice et dans le client léger.
- Multiserveur : dans les Réglages de l'app, ajouter un compte sur « Serveur B » et passer de l'un à l'autre.

## 5. Interopérabilité (3 min)

- AntennaPod : s'abonner à un podcast et écouter quelques minutes, synchroniser ; l'abonnement et la position apparaissent dans nos clients, et inversement ([ADR 0011](adr/0011-synchronisation-clients-tiers.md)).
- Le flux RSS de la playlist dans newsboat.

## 6. Terminal et statistiques (2 min)

- `podcast stats` : les douze derniers mois en barres.
- `podcast episodes kotlin --status new --play` : la TUI Mosaic qui lit.
- « Mon écoute » dans les Réglages : le même calcul, en graphique Swift Charts sur iPhone et sur Mac.

## Si quelque chose ne marche pas

- Coupure du réseau : les positions attendent et partent dès que le serveur répond de nouveau.
- Un client planté : les autres continuent, l'état est sur le serveur. Rouvrir l'app, elle reprend la session.
