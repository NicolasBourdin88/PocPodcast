# POC jetables (étape 2)

Chaque dossier est un build Gradle autonome, hors du projet principal et de la CI, qui sert à trancher une étude technique. Le code n'est pas destiné à être réutilisé tel quel ; seules les conclusions comptent, et elles sont dans `docs/adr/`.

Les POC réutilisent le wrapper et le catalogue de versions du projet :

```bash
./gradlew -p poc/hypermedia test
```

```bash
./gradlew -p poc/persistence test
```

```bash
./gradlew -p poc/auth test
```

```bash
./gradlew -p poc/player run
```

```bash
./gradlew -p poc/gpodder-sync run
```

| POC | Étude | ADR |
|---|---|---|
| `hypermedia/` | 1. Format hypermédia | [0003](../docs/adr/0003-format-hypermedia.md) |
| `persistence/` | 5. Persistance | [0004](../docs/adr/0004-persistance.md) |
| `auth/` | 6. Authentification et droits | [0005](../docs/adr/0005-authentification-et-droits.md) |
| `player/` | 3. Lecture audio (desktop : JavaFX Media sur de vrais épisodes, son coupé ; rapport dans `build/poc-report.md`) | [0008](../docs/adr/0008-lecture-audio.md) |
| `gpodder-sync/` | 2. Synchronisation avec les clients tiers (serveur gPodder en mémoire sur le port 8090, branché sur un vrai AntennaPod ; traces dans `traces/`) | [0011](../docs/adr/0011-synchronisation-clients-tiers.md) |
