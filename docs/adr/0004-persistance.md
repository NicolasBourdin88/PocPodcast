# ADR 0004 — Persistance : PostgreSQL + Flyway + SQLDelight

- Statut : accepté
- Date : 13 octobre 2026
- Étude : n° 5 du plan (étape 2) — POC dans `poc/persistence/`

## Question

Quelle bibliothèque pour accéder à PostgreSQL côté serveur : **Exposed** ou **SQLDelight** ? Et quoi côté clients pour le hors-ligne : **SQLDelight** ou **Room KMP** ?

## POC

- **Schéma** : utilisateurs, podcasts, épisodes, `playback_states` (état par utilisateur, fichiers partagés).
- **Migration** : une seule, `V1__init.sqm`, appliquée par **Flyway** et lue par SQLDelight comme source du schéma.
- **Requêtes** : les trois dont a besoin l'Accueil (upsert de la position, épisodes en cours, derniers épisodes avec l'état de l'utilisateur en `LEFT JOIN`), écrites une fois avec chaque bibliothèque.
- **Exécution** : sur un vrai PostgreSQL 18 embarqué (zonky), sans Docker.

| Critère | Exposed 1.5.1 | SQLDelight 2.4.1 |
|---|---|---|
| Résultats identiques sur le scénario multi-utilisateurs | oui | oui |
| Erreur de colonne détectée | à l'exécution | **à la compilation** (« No column found with name statut ») |
| Le code reste aligné sur le schéma | non : les objets `Table` recopient la migration à la main | **oui** : le schéma *est* la migration Flyway |
| Fonctions PostgreSQL (`ON CONFLICT`, `COALESCE`, `TIMESTAMPTZ`) | oui (DSL `upsert`) | oui (SQL natif, types `java.time`) |
| Requêtes dynamiques (filtres optionnels) | **facile** (DSL composable) | requêtes statiques : motif `(:x IS NULL OR col = :x)` |
| Lignes de code des trois requêtes | ~60 (tables + DSL) | ~25 SQL + ~15 Kotlin |
| 1 000 upserts / 1 000 lectures | 196 ms / 130 ms | 45 ms / 80 ms |
| Utilisable aussi côté client KMP | non (JVM seulement) | **oui** (Android, iOS, JVM, dialecte SQLite) |
| Coroutines | `suspendTransaction` (JDBC bloquant en dessous) | JDBC bloquant, à appeler sur `Dispatchers.IO` ; extension `Flow` côté client |

Les temps ne sont qu'indicatifs. Exposed ouvre une transaction par appel alors que SQLDelight est en auto-commit, et l'écart n'aura aucun impact à notre échelle.

## Décision

**Serveur** :
- PostgreSQL ;
- migrations **Flyway** en SQL, dans `server/src/main/sqldelight/migrations/V<n>__<nom>.sqm` ;
- accès via **SQLDelight**, dialecte PostgreSQL, schéma dérivé des migrations ;
- pool HikariCP ;
- pour les tests, PostgreSQL embarqué (zonky), pour ne pas exiger Docker.

Justification :
1. Les requêtes sont vérifiées contre le schéma à la compilation : moins de bugs dans un groupe qui débute en Kotlin.
2. Le SQL écrit est du SQL standard, déjà connu des étudiants.
3. Le même outil sert côté client.

**Clients** : **SQLDelight**, dialecte SQLite, pour le cache hors-ligne, quand il sera nécessaire (étape 5 ou 6). Room KMP (2.8) est une alternative crédible. Nous l'écartons pour garder un seul outil dans l'équipe et éviter KSP. Le schéma client sera distinct de celui du serveur (cache partiel, autre dialecte) ; seuls les modèles de `:shared` sont communs.

## Conséquences

- **Recherche multicritère** (étape 9) : on part de requêtes SQL à paramètres optionnels et de la recherche plein texte PostgreSQL. Si ça devient ingérable, cette seule requête pourra passer par du JDBC dynamique, sans remettre en cause le reste.
- **Accès bloquant** : les appels SQLDelight côté serveur sont enveloppés dans `withContext(Dispatchers.IO)` dans les dépôts.
- **Développement local** : `docker compose` avec PostgreSQL pour `:server:run` ; les tests n'en ont pas besoin.
- **Étape 3** : remplacer `FakePodcastRepository` côté serveur par un dépôt SQLDelight.
