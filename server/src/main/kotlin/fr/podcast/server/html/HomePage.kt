package fr.podcast.server.html

import fr.podcast.shared.home.EpisodeItem
import fr.podcast.shared.home.HomeState
import fr.podcast.shared.model.EpisodeStatus
import kotlinx.html.FlowContent
import kotlinx.html.HTML
import kotlinx.html.UL
import kotlinx.html.a
import kotlinx.html.aside
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.footer
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.head
import kotlinx.html.header
import kotlinx.html.lang
import kotlinx.html.li
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.nav
import kotlinx.html.progress
import kotlinx.html.script
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.stream.createHTML
import kotlinx.html.style
import kotlinx.html.title
import kotlinx.html.ul
import kotlinx.html.unsafe
import kotlin.math.absoluteValue

// Same palette and choice rule as the Compose client, so a podcast has the same color everywhere.
private val ARTWORK_COLORS = listOf("#6750A4", "#0B6E4F", "#B3261E", "#1F5FA8", "#8A5A00")

private const val HTMX_URL = "https://unpkg.com/htmx.org@2.0.11/dist/htmx.min.js"

private val navigation = listOf(
    "Accueil" to "/",
    "Explorer" to "/explore",
    "Bibliothèque" to "/library",
    "Réglages" to "/settings",
)

fun HTML.homePage(state: HomeState) {
    lang = "fr"
    head {
        meta(charset = "utf-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1")
        title("Podcast — Accueil")
        script(src = HTMX_URL) {}
        style { unsafe { +STYLES } }
    }
    body {
        nav(classes = "nav") {
            div("brand") { +"Podcast" }
            ul {
                navigation.forEach { (label, href) ->
                    li { a(href = href) { if (href == "/") attributes["aria-current"] = "page"; +label } }
                }
            }
        }
        main {
            header { h1 { +"Accueil" } }
            if (state.resume.isNotEmpty()) {
                section {
                    h2 { +"Reprendre l'écoute" }
                    ul("cards") {
                        state.resume.forEach { item ->
                            li("card") {
                                artwork(item)
                                div("card-body") {
                                    span("overline") { +item.podcast.title }
                                    span("title") { +item.episode.title }
                                    progress {
                                        attributes["max"] = "1"
                                        attributes["value"] = item.episode.progress.toString()
                                    }
                                    span("meta") { +"${item.episode.remainingMinutes} min restantes" }
                                }
                            }
                        }
                    }
                }
            }
            section {
                div("section-header") {
                    h2 { +"Nouveaux épisodes" }
                    button(classes = "refresh") {
                        attributes["hx-get"] = "/fragments/latest"
                        attributes["hx-target"] = "#latest"
                        attributes["hx-swap"] = "outerHTML"
                        +"Rafraîchir"
                    }
                }
                latestFragment(state.latest)
            }
        }
        state.resume.firstOrNull()?.let { item ->
            footer("mini-player") {
                artwork(item)
                div("card-body") {
                    span("title") { +item.episode.title }
                    span("meta") { +item.podcast.title }
                }
                button(classes = "play") { attributes["aria-label"] = "Lecture"; +"▶" }
            }
        }
    }
}

/** List of latest episodes, also served alone at /fragments/latest for htmx. */
fun FlowContent.latestFragment(items: List<EpisodeItem>) {
    ul("episodes") { latestItems(items) }
}

fun latestFragmentHtml(items: List<EpisodeItem>): String =
    createHTML().ul("episodes") { latestItems(items) }

private fun UL.latestItems(items: List<EpisodeItem>) {
    attributes["id"] = "latest"
    items.forEach { item ->
        val episode = item.episode
        val status = when (episode.status) {
            EpisodeStatus.NEW -> "Nouveau"
            EpisodeStatus.IN_PROGRESS -> "${episode.remainingMinutes} min restantes"
            EpisodeStatus.PLAYED -> "Écouté"
        }
        li("episode") {
            artwork(item)
            div("card-body") {
                span("overline") { +"${item.podcast.title} · ${episode.publishedLabel}" }
                a(href = "/episodes/${episode.id}", classes = "title") { +episode.title }
                span("meta") { +"${episode.durationLabel} · $status" }
            }
        }
    }
}

private fun FlowContent.artwork(item: EpisodeItem) {
    val initials = item.podcast.title.split(' ').filter { it.isNotBlank() }.take(2)
        .joinToString("") { it.first().uppercase() }
    aside("artwork") {
        attributes["style"] = "background: ${ARTWORK_COLORS[item.podcast.id.hashCode().absoluteValue % ARTWORK_COLORS.size]}"
        attributes["aria-hidden"] = "true"
        +initials
    }
}

private val STYLES = """
    :root {
      --bg: #fdf7ff; --surface: #f3edf7; --card: #e8def8; --text: #1d1b20; --muted: #625b71; --accent: #6750a4;
      color-scheme: light dark;
    }
    @media (prefers-color-scheme: dark) {
      :root { --bg: #141218; --surface: #1d1b20; --card: #4a4458; --text: #e6e0e9; --muted: #cac4d0; --accent: #d0bcff; }
    }
    * { box-sizing: border-box; }
    body { margin: 0; font-family: system-ui, sans-serif; background: var(--bg); color: var(--text); }
    main { padding: 16px 16px 160px; max-width: 960px; }
    h1 { font-size: 1.5rem; margin: 8px 0 16px; }
    h2 { font-size: 1.05rem; margin: 24px 0 12px; }
    ul { list-style: none; margin: 0; padding: 0; }
    a { color: inherit; text-decoration: none; }

    .nav { position: fixed; bottom: 0; left: 0; right: 0; background: var(--surface); z-index: 2; }
    .nav .brand { display: none; }
    .nav ul { display: flex; justify-content: space-around; }
    .nav a { display: block; padding: 14px 8px; color: var(--muted); font-size: .85rem; }
    .nav a[aria-current] { color: var(--text); font-weight: 600; }

    .cards { display: grid; gap: 12px; }
    .card, .episode, .mini-player { display: flex; gap: 12px; align-items: center; }
    .card { background: var(--card); border-radius: 16px; padding: 12px; }
    .episode { padding: 10px 0; }
    .card-body { display: flex; flex-direction: column; gap: 4px; min-width: 0; flex: 1; }
    .overline, .meta { font-size: .8rem; color: var(--muted); }
    .title { font-weight: 500; }
    progress { appearance: none; width: 100%; height: 4px; border: none; border-radius: 2px; background: var(--surface); }
    progress::-webkit-progress-bar { background: var(--surface); border-radius: 2px; }
    progress::-webkit-progress-value { background: var(--accent); border-radius: 2px; }
    progress::-moz-progress-bar { background: var(--accent); border-radius: 2px; }
    .artwork {
      flex: none; width: 56px; height: 56px; border-radius: 10px; display: grid; place-items: center;
      color: white; font-weight: 700;
    }
    .section-header { display: flex; align-items: center; justify-content: space-between; }
    button { font: inherit; border: none; border-radius: 999px; background: var(--card); color: var(--text); padding: 8px 16px; cursor: pointer; }
    .htmx-request.refresh { opacity: .5; }

    .mini-player { position: fixed; left: 0; right: 0; bottom: 52px; background: var(--surface); padding: 8px 16px; border-top: 3px solid var(--accent); }
    .mini-player .artwork { width: 40px; height: 40px; font-size: .8rem; }
    .mini-player .title { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

    @media (min-width: 840px) {
      .nav { top: 0; right: auto; width: 240px; padding: 16px 12px; }
      .nav .brand { display: block; font-size: 1.3rem; padding: 8px 16px 16px; }
      .nav ul { flex-direction: column; }
      .nav a { padding: 12px 16px; border-radius: 999px; font-size: 1rem; }
      .nav a[aria-current] { background: var(--card); }
      main { margin-left: 240px; padding: 24px 32px 120px; }
      .cards { grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); }
      .mini-player { left: 240px; bottom: 0; }
    }
""".trimIndent()
