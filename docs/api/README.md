# Contrat de l'API (étape 3)

| Document | Contenu |
|---|---|
| [conventions.md](conventions.md) | Règles communes : représentations, liens, actions HAL-FORMS, pagination, authentification, erreurs |
| [modele-de-donnees.md](modele-de-donnees.md) | Modèle de données complet (PostgreSQL) et étape de création de chaque table |
| [podcast-api.yaml](../../server/src/main/resources/openapi/podcast-api.yaml) | Spécification OpenAPI 3.1 de chaque ressource |

La spécification est servie par le serveur, avec Swagger UI, sur http://localhost:8080/docs.

Les modèles JSON (`Podcast`, `Episode`) sont les classes sérialisables de `:shared`. Le serveur et le SDK utilisent donc le même code pour écrire et lire les ressources. Les tests de contrat de `:server` vérifient trois choses :
1. chaque opération de la spécification existe côté serveur ;
2. chaque route du serveur est documentée ;
3. chaque réponse respecte son schéma.
