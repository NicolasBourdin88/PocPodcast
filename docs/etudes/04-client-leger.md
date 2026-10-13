# Fiche d'étude 4 — Client léger

**Objectif** : confirmer, chiffres à l'appui, le choix déjà pris lors du cadrage (`CLAUDE.md`) : HTML rendu par le serveur + htmx plutôt que Compose pour le Web (Wasm).

## Ce qui existe déjà

Le POC de l'étape 1 tourne déjà. Il se compose de :
- **`:server`** : kotlinx.html et htmx 2.0.11 ;
- **un fragment** `/fragments/latest`, rafraîchi par htmx ;
- **un CSS responsive** (barre en bas, barre latérale à partir de 840 px) ;
- **des textes et couleurs** partagés avec les autres clients (ADR 0002).

## Questions à trancher

1. Poids et temps de chargement : page HTML + htmx face à une application Compose Wasm minimale affichant le même Accueil.
2. Compose Web est-il toujours en bêta à la date de l'étude ? Quels navigateurs le supportent (WasmGC) ?
3. Effort pour un formulaire avec validation (ajout d'abonnement par URL) dans chaque approche.
4. Accessibilité et SEO : un client léger doit-il être lisible sans JavaScript ?
5. CORS : un client Wasm qui veut lire un flux RSS directement est bloqué ; confirmer qu'il faudrait tout faire passer par le serveur.
6. Comment rendre les actions HAL-FORMS (ADR 0003) en formulaires HTML / attributs htmx à partir du même modèle `Affordance` ?

## Critères

Poids transféré (Ko), temps jusqu'au premier affichage sur une connexion 3G simulée (outils de développement du navigateur), effort de développement, accessibilité, réutilisation du code de `:shared`.

## POC suggéré (`poc/web-client/`)

- Une application Compose Wasm minimale (template du wizard KMP, cible Web) affichant l'Accueil à partir de `FakePodcastRepository`.
- Mesurer avec les outils de développement : taille totale, nombre de requêtes, temps de chargement. Comparer avec `./gradlew :server:run`.
- Prototyper le rendu d'une `Affordance` en `<form hx-put=…>` côté serveur.

## Livrable

Un ADR « Client léger » qui confirme (ou remet en cause) htmx, avec :
- les mesures ;
- la façon de rendre les actions hypermédia en HTML.
