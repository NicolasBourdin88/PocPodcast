package fr.podcast.shared.navigation

/** Top-level screens, in navigation order. [path] is the thin client's URL. */
enum class Destination(val path: String) {
    Home("/"),
    Explore("/explore"),
    Library("/library"),
    Settings("/settings"),
}
