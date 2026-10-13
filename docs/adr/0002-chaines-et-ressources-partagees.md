# ADR 0002 — Chaînes traduites et ressources partagées

- Statut : accepté
- Date : 13 octobre 2026

## Contexte

L'interface doit exister en français et en anglais sur tous les clients : Compose (Android, iOS, desktop), client léger HTML et CLI/TUI. Avec les ressources propres à chaque plateforme (`strings.xml` Android, `Localizable.strings`, textes en dur côté serveur et CLI), chaque texte serait écrit quatre fois et les traductions finiraient par diverger.

Options étudiées :

| Option | Pour | Contre |
|---|---|---|
| Ressources Compose Multiplatform (`composeResources/values*/strings.xml`) | Format XML standard, `stringResource()` | Utilisable seulement là où il y a Compose. Le serveur ne peut pas choisir la langue requête par requête : le constructeur de `ResourceEnvironment` est interne (vérifié sur la 1.12.1). Le CLI devrait embarquer le runtime de ressources. |
| Ressources natives par plateforme | Outillage de traduction natif | Quatre copies, aucune vérification croisée. |
| **Catalogue Kotlin dans `:shared`** | Un seul endroit, utilisable partout (y compris le serveur avec `Accept-Language`). Une traduction manquante est une erreur de compilation. Pluriels et formats en simples fonctions. | Les traducteurs éditent du Kotlin. Pas d'outillage de traduction externe. |

## Décision

Tout ce qui est commun aux clients vit dans `:shared` :

- **`fr.podcast.shared.i18n`**
  - L'interface `Strings`, implémentée par `FrenchStrings` et `EnglishStrings`.
  - `Language`, qui choisit la langue à partir d'une locale : `fromTag`, et `negotiate` pour une liste de préférences. La langue par défaut est l'anglais.
- **Formats** : la durée, la date courte (le modèle porte maintenant une vraie `LocalDate` via kotlinx-datetime) et le statut d'un épisode sont formatés par `Strings`. Le modèle reste neutre : `durationLabel` a été retiré d'`Episode`, et le HAL n'expose que des données.
- **`fr.podcast.shared.design.DesignTokens`** : couleurs claires et sombres (base Material 3) et palette des pochettes provisoires, avec `Podcast.artworkColor` et `Podcast.initials`. Compose en fait son `ColorScheme`, le serveur ses variables CSS et la TUI sa couleur d'accent.
- **`fr.podcast.shared.navigation.Destination`** : les écrans de premier niveau, leur ordre et leur URL côté client léger.

Choix de la langue :

| Client | Source |
|---|---|
| Compose (Android, iOS, desktop) | `Locale.current`, fournie par `PodcastTheme` via `LocalStrings` |
| Client léger | En-tête `Accept-Language` (réponses `Vary: Accept-Language`) |
| CLI / TUI | Option `--lang`, sinon la locale du système |

Déclarations côté plateforme : `CFBundleLocalizations` dans `Info.plist` (sinon iOS ne transmet pas la locale française à l'app) et `localeConfig` sur Android (choix de la langue par app à partir d'Android 13). Ces deux listes doivent rester alignées sur `Language`.

## Conséquences

- Ajouter une langue : une valeur dans `Language`, un objet `XxxStrings`, et les deux déclarations de plateforme.
- Ajouter un texte : un membre dans `Strings`. La compilation signale chaque langue où il manque.
- Restent en anglais : les messages génériques de Clikt (`--help`, erreurs de parsing), qu'on pourra traduire avec une `Localization` Clikt.
- Les icônes restent propres à chaque client (Material Icons dans Compose, texte dans le HTML et la TUI).
