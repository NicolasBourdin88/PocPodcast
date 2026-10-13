package fr.podcast.shared.navigation

/**
 * Top-level screens, in navigation order. [path] is the resource the thin client shows:
 * the same URL as the API, in its HTML representation (docs/api/conventions.md).
 */
enum class Destination(val path: String) {
    Home("/"),
    Explore("/search"),
    Library("/subscriptions"),
    Settings("/settings"),
}
