# ADR 0006 — Comptes et clients multiserveurs

- Statut : accepté
- Date : 13 octobre 2026
- Étape : 3 (contrat)

## Contexte

Le sujet impose un **client mobile multiserveur** : la même application doit pouvoir se connecter à plusieurs serveurs, par exemple celui du groupe et celui d'un ami. L'ADR 0005 a fixé l'authentification par jeton d'appareil. Il reste à préciser :
- ce que le client garde ;
- comment il se connecte sans connaître l'API à l'avance ;
- comment il passe d'un serveur à l'autre.

## Décision

**Un compte = un serveur + un utilisateur + un jeton.** `Account(serverUrl, serverName, username, displayName, token)` dans `:shared`. Un appareil peut garder plusieurs comptes, sur plusieurs serveurs ou sur le même. **Un seul est actif** : toute l'application (Accueil, abonnements…) montre ce compte, et Réglages permet d'en changer.

On n'agrège pas plusieurs serveurs dans un même écran : il faudrait dédoublonner les podcasts communs et arbitrer des états d'écoute contradictoires. On pourra y revenir si le besoin apparaît.

**Connexion par l'hypermédia** (`AccountService`), le client ne connaissant que l'adresse saisie :
1. `GET /` : la racine anonyme donne le nom du serveur, `apiVersion` et le template `create-token`. Sans eux, ce n'est « pas un serveur de podcasts ».
2. Le template est soumis avec l'identifiant, le mot de passe et le nom de l'appareil, et le serveur renvoie le jeton. Le mot de passe n'est jamais stocké.
3. `GET /me`, via le lien du jeton, fournit le nom affiché.

**Précision de l'ADR 0005** : le jeton s'obtient avec un **corps JSON**, et non en HTTP Basic, pour que l'action soit décrite par un template HAL-FORMS. Basic reste réservé aux clients gPodder (étape 8).

**Déconnexion** : le client suit `me`, puis le template `logout` (`DELETE /auth/tokens/current`). Le jeton est révoqué côté serveur, puis le compte est oublié localement, même si le serveur est injoignable.

**Données** : `RemotePodcastRepository` part de la racine et suit les liens.
- Pour retrouver une ressource à partir de son identifiant, il utilise les liens à gabarit `episode` et `podcast` de la racine.
- Les actions passent par les templates HAL-FORMS, et ne sont tentées que si le serveur les propose.

**Stockage** : une interface `KeyValueStore`, implémentée par plateforme.

| Plateforme | Stockage | Adresse proposée par défaut |
|---|---|---|
| Android | `SharedPreferences` privées de l'app | `http://10.0.2.2:8080` (la machine hôte, vue de l'émulateur) |
| iOS | `NSUserDefaults` | `http://localhost:8080` (le simulateur partage le réseau du Mac) |
| Desktop | `java.util.prefs` (nœud `fr/podcast/desktop`) | `http://localhost:8080` |
| CLI | `java.util.prefs` (nœud `fr/podcast/cli`) | `http://localhost:8080` |

**Réseau en développement** : HTTP en clair n'est autorisé que vers les serveurs de développement.
- **Android** : configuration réseau limitée à `10.0.2.2` et `localhost`.
- **iOS** : `NSAllowsLocalNetworking`.
- **Production** : partout ailleurs, HTTPS.

**Erreurs** : elles sont typées (`ApiException` : injoignable, pas un serveur de podcasts, non autorisé…). Leur message est traduit par une seule fonction partagée, utilisée par les apps et le CLI. Un jeton révoqué conduit à proposer une nouvelle connexion.

## Conséquences

- **Jeton** : il est rangé avec les préférences de l'application, protégées par le bac à sable du système mais non chiffrées. Le risque est borné : le jeton est révocable appareil par appareil, et le mot de passe n'est jamais stocké. Le passage au trousseau (Keychain sur iOS et macOS, Keystore sur Android) est prévu avec l'étape 6.
- **Portabilité** : les clients ne construisent aucune URL. Le serveur peut réorganiser ses chemins sans casser les clients, tant que les relations et les noms de templates ne changent pas.
- **Garde-fous** : `SdkTest` (serveur) fait tourner le vrai SDK contre le vrai serveur. `ContractTest` garde la spécification et le serveur alignés.
