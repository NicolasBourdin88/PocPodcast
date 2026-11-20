# Images du README

| Fichier | Rôle |
|---|---|
| `architecture.py` | Écrit le schéma d'architecture, clair et sombre, dans `docs/images/` |
| `boards.py` | Assemble les planches de captures (`docs/images/accueil.webp`, `bibliotheque.webp`) |
| `webshot.swift` | Rend une page dans WebKit et l'enregistre en PNG (client web, planches) |

Il faut Python 3, Xcode (pour `swiftc`) et `cwebp` (`brew install webp`).

```bash
python3 tools/readme/architecture.py
```

## Refaire les captures

Les planches montrent la même page, sur le même compte, au même instant : les données ne doivent pas changer entre deux captures.

1. Lancer le serveur 8080 sans relecture des flux : `REFRESH_INTERVAL_MINUTES=100000 ./gradlew :server:run`.
2. Mettre chaque client en mode sombre, connecté au compte d'Alice. Fixer l'horloge des téléphones à 9:41 :
   - simulateur iOS : `xcrun simctl ui booted appearance dark` et `xcrun simctl status_bar booted override --time 9:41` ;
   - émulateur Android : `adb shell cmd uimode night yes`, puis le mode démo de la barre d'état (`settings put global sysui_demo_allowed 1`, puis `am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0941`).
3. Enregistrer les captures dans un même dossier, `captures/` ici, pour chaque page : `home` (Accueil) et `library` (Bibliothèque).

| Client | Capture de l'Accueil (remplacer `home` par `library` pour la Bibliothèque) |
|---|---|
| Web | `captures/webshot "http://localhost:8080/dev/login/alice?next=/" captures/web-home.png 1440 796 dark 4` (`?next=/subscriptions` pour la Bibliothèque), après `swiftc -O -o captures/webshot tools/readme/webshot.swift` |
| Mac | la fenêtre à 1440 × 900 points, puis `open build/apple-derived/Build/Products/Debug/Podcast.app --args -snapshot $PWD/captures/mac-home.png -appearance dark -tab home -click ""` |
| Desktop | `PODCAST_TAB=home PODCAST_WINDOW=1440x828 PODCAST_SNAPSHOT=$PWD/captures/desktop-home.png ./gradlew :composeApp:run` |
| Android | ouvrir l'onglet, puis `adb exec-out screencap -p > captures/android-home.png` |
| iPhone | `xcrun simctl launch --terminate-running-process booted fr.podcast.apple -tab home`, puis `xcrun simctl io booted screenshot captures/iphone-home.png` |
| Terminal | `captures/terminal-home.txt` : la ligne `$ podcast list`, puis la sortie de la commande |

Le web et le desktop sont capturés à la hauteur de la fenêtre Mac moins sa barre de titre : les trois fenêtres ont la même taille sur la planche. `-click ""` active la fenêtre Mac avant la capture ; sans lui, elle apparaît grisée.

4. Assembler les planches ; la date affichée est en second argument :

```bash
python3 tools/readme/boards.py captures "vendredi 20 novembre 2026, 9 h 41"
```
