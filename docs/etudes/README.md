# Études techniques (étape 2)

Chaque étude produit un ADR de 1 à 2 pages dans `docs/adr/` : question, options, critères, POC jetable (dans `poc/`), décision. Les ADR 0003 à 0005 servent d'exemples de format.

| # | Étude | État | Livrable |
|---|---|---|---|
| 1 | Format hypermédia | ✅ Tranchée | [ADR 0003](../adr/0003-format-hypermedia.md) |
| 2 | Synchronisation avec les clients tiers | 📋 À mener | [Fiche](02-synchronisation-clients-tiers.md) |
| 3 | Lecture audio multiplateforme | 📋 À mener | [Fiche](03-lecture-audio.md) |
| 4 | Client léger | 📋 À confirmer | [Fiche](04-client-leger.md) |
| 5 | Persistance | ✅ Tranchée | [ADR 0004](../adr/0004-persistance.md) |
| 6 | Authentification et droits | ✅ Tranchée | [ADR 0005](../adr/0005-authentification-et-droits.md) |

Pour un POC : créer `poc/<nom>/` en copiant `poc/hypermedia/settings.gradle.kts`, qui réutilise le catalogue de versions du projet, puis lancer `./gradlew -p poc/<nom> test`.
