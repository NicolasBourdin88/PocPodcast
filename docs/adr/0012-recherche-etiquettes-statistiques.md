# ADR 0012 — Recherche, étiquettes et statistiques

- Statut : accepté
- Date : 18 novembre 2026
- Complète : ADR 0007 (abonnements), ADR 0008 (position d'écoute), ADR 0010 (favoris et notes)

## Contexte

L'étape 9 demande :
- une **recherche multicritère** : note, description, date, statut, mots-clés ;
- des **étiquettes**, la notation et les favoris existant depuis l'étape 7 ;
- des **statistiques d'écoute**.

Le serveur ne garde jusqu'ici que l'**état** de chaque épisode (position, statut) : rien ne dit combien de temps on a écouté, ni quand.

## Décision

### Recherche d'épisodes

- **Où** : `GET /episodes` reçoit des filtres, tous facultatifs et cumulables.

  | Filtre | Effet |
  |---|---|
  | `q` | Chaque mot doit figurer, sans tenir compte de la casse, dans le titre, la description ou le titre du podcast |
  | `status` | `NEW`, `IN_PROGRESS` ou `PLAYED` |
  | `favorite=true` | Les favoris |
  | `minRating` | Note minimale, de 1 à 5 |
  | `from`, `to` | Période de publication (dates `AAAA-MM-JJ`, bornes comprises) |
  | `podcast` | Un podcast (son identifiant) |
  | `tag` | Les podcasts portant cette étiquette |

- **Portée** : les épisodes des abonnements de l'utilisateur, les plus récents d'abord, page par page comme aujourd'hui.
- **Lien** : la racine donne `episodes` sous forme de gabarit avec tous ces paramètres. Les clients le remplissent sans construire d'URL (ADR 0006).
- **Bibliothèque** : `GET /subscriptions` accepte aussi `q` (titre, auteur, description ou note de l'abonnement) et `tag`.

### Étiquettes

- **Sur les abonnements**, comme dans AntennaPod : elles regroupent les podcasts (« trajets », « tech »…) et servent de filtre partout.
- **Règles communes** (`TagRules`, dans `:shared`) : 10 étiquettes au plus par abonnement, de 1 à 30 caractères, sans virgule. Les doublons qui ne diffèrent que par la casse sont fusionnés.
- **Droits** : comme la note d'un abonnement. Le propriétaire et un partage `WRITE` les changent (template `update`, champ `tags`) ; un partage `READ` les voit.
- **Stockage** : table `subscription_tags`. Une étiquette disparaît avec son abonnement.

### Statistiques d'écoute

- **Mesure** : le temps écouté se compte en **durée d'épisode**, comme dans AntennaPod, une écoute à 1,5× valant donc 1,5 fois le temps passé.
  - Chaque nouvelle position ajoute l'écart avec la précédente.
  - Cet écart n'est compté que s'il reste plausible : positif, et au plus 3 fois le temps écoulé depuis la position précédente, plus 30 secondes. Un saut en avant ne compte donc pas.
  - Une action gPodder `play` compte `position − started` (ADR 0011).
  - Un épisode qui passe à « écouté » compte comme **terminé**.
- **Stockage** : `listening_days`, un total par utilisateur, jour (UTC) et podcast. Le podcast y est désigné par son flux et son titre, pour que l'historique survive à un podcast que plus personne ne suit.
- **Point de départ** : la migration estime l'historique à partir de l'état existant (durée des épisodes écoutés, position des épisodes en cours), daté du dernier changement de chaque état.
- **Ressource** : `GET /me/stats` (lien `stats` de `/me`) donne :
  - le total, les 7 et 30 derniers jours et le nombre d'épisodes terminés ;
  - les podcasts les plus écoutés ;
  - les 12 derniers mois.

### Clients

- **Apps** :
  - Bibliothèque : un champ de recherche et les étiquettes en filtres, puis un onglet « Épisodes » pour la recherche multicritère ;
  - étiquettes modifiables avec la note d'un abonnement ;
  - Réglages : section « Mon écoute ».
- **Client léger** :
  - Bibliothèque filtrable ;
  - page de recherche d'épisodes (`/episodes` en HTML) ;
  - statistiques dans les Réglages.
- **CLI** : `episodes` (avec ses filtres ; `--play` lit ce qu'elle trouve), `tags`, `stats` ; `subscriptions` se filtre par mots et `--tag`.

## Conséquences

- La migration `V8` ajoute `subscription_tags` et `listening_days` (remplie à partir de l'état existant).
- Le jour est celui de l'UTC : une écoute à minuit et demi en France compte pour la veille. C'est acceptable pour des statistiques par mois.
- La recherche lit les colonnes à chaque requête (expressions régulières de PostgreSQL). Un index plein texte ne s'imposerait qu'avec de gros catalogues.
- Hors périmètre : étiquettes sur les épisodes (les favoris et les playlists statiques jouent ce rôle), règle d'étiquette dans les playlists dynamiques, statistiques du serveur pour un administrateur.
