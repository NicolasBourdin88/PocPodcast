# Modèle de données

Base PostgreSQL du service, gérée par migrations Flyway et lue par SQLDelight (ADR 0004). Le modèle complet est décrit ici. Chaque table est créée par la migration de l'étape qui l'utilise : on ne crée pas de table sans code qui s'en sert.

## Principes

- **Catalogue mutualisé, état par utilisateur.**
  - Les podcasts, les épisodes et leurs fichiers audio existent une seule fois sur le serveur, quel que soit le nombre d'abonnés.
  - Ce qui dépend de l'utilisateur vit dans des tables séparées, clé `user_id` : abonnement, annotation, position, statut, playlists.
- **Propriétaire + partages** (ADR 0005) : les ressources partageables (abonnements, playlists, épisodes recommandés) ont un `owner`, et `shares` donne `READ` ou `WRITE` à d'autres utilisateurs.
- **Secrets** : seul le hash des mots de passe (Argon2id) et des jetons (SHA-256) est stocké.
- **Clés** : `BIGSERIAL` en base, exposées comme chaînes opaques dans l'API.
- **Dates** : `TIMESTAMPTZ` en base, ISO 8601 UTC dans l'API.

## Diagramme

```mermaid
erDiagram
    users ||--o{ api_tokens : "possède"
    users ||--o{ http_sessions : "possède"
    users ||--o{ subscriptions : "s'abonne"
    podcasts ||--o{ subscriptions : "suivi par"
    podcasts ||--o{ episodes : "publie"
    users ||--o{ playback_states : "écoute"
    episodes ||--o{ playback_states : "écouté par"
    episodes ||--o| media_files : "fichier mutualisé"
    users ||--o{ playlists : "possède"
    playlists ||--o{ playlist_items : "contient"
    episodes ||--o{ playlist_items : "figure dans"
    playlists ||--o| playlist_feeds : "exposée en RSS"
    users ||--o{ shares : "reçoit"

    users {
        bigint id PK
        text username UK
        text display_name
        text password_hash "Argon2id"
        text role "USER | ADMIN"
        timestamptz created_at
    }
    api_tokens {
        bigint id PK
        bigint user_id FK
        text token_hash UK "SHA-256"
        text device_name
        timestamptz created_at
        timestamptz last_used_at
        timestamptz revoked_at
    }
    http_sessions {
        text id PK
        text value
        timestamptz expires_at
    }
    podcasts {
        bigint id PK
        text feed_url UK
        text title
        text author
        text description
        text artwork_url
        text website_url
        timestamptz last_refreshed_at
    }
    subscriptions {
        bigint id PK
        bigint user_id FK
        bigint podcast_id FK
        text note "annotation"
        timestamptz subscribed_at
    }
    episodes {
        bigint id PK
        bigint podcast_id FK
        text guid "unique par podcast"
        text title
        text description
        timestamptz published_at
        int duration_seconds
        text audio_url "enclosure RSS"
    }
    playback_states {
        bigint user_id PK
        bigint episode_id PK
        int position_seconds
        text status "NEW | IN_PROGRESS | PLAYED"
        timestamptz updated_at
    }
    media_files {
        bigint id PK
        bigint episode_id FK
        text storage_path
        bigint size_bytes
        text status "PENDING | READY | FAILED"
    }
    playlists {
        bigint id PK
        bigint owner_id FK
        text title
        text kind "STATIC | DYNAMIC"
        jsonb rules "playlists dynamiques"
    }
    playlist_items {
        bigint playlist_id PK
        bigint episode_id PK
        int position
    }
    playlist_feeds {
        bigint playlist_id PK
        text secret UK "URL à capacité"
    }
    shares {
        text resource_type PK "SUBSCRIPTION | PLAYLIST | EPISODE"
        bigint resource_id PK
        bigint grantee_id PK
        text permission "READ | WRITE"
    }
```

## Tables par étape

| Table | Rôle | Créée à |
|---|---|---|
| `users`, `api_tokens`, `http_sessions` | Comptes, jetons d'appareil, sessions du client léger | Étape 3 (`V1`) |
| `podcasts`, `episodes` | Catalogue mutualisé | Étape 3 (`V1`) |
| `subscriptions` | Abonnements par utilisateur (+ annotation) | Étape 3 (`V1`) |
| `playback_states` | Position et statut par utilisateur et par épisode | Étape 3 (`V1`) |
| `media_files` | Fichiers audio téléchargés et mutualisés, purge | Étape 4 |
| `shares` | Partages et droits | Étape 6 |
| `playlists`, `playlist_items`, `playlist_feeds` | Playlists statiques et dynamiques, flux RSS | Étape 7 |

## Règles

- **Absence d'état** : un épisode sans ligne dans `playback_states` vaut `NEW`, position 0.
- **Écouté** : marquer un épisode comme écouté met `status = PLAYED` et `position_seconds = duration_seconds`. Le marquer comme non écouté supprime la ligne.
- **Suppression d'un podcast** : elle supprime ses épisodes, et donc les états d'écoute qui s'y rapportent (`ON DELETE CASCADE`). Un podcast n'est supprimé que lorsqu'il n'a plus d'abonnés (étape 4).
- **Unicité** : `(podcast_id, guid)` identifie un épisode lors des rafraîchissements du flux. `feed_url` identifie un podcast.
