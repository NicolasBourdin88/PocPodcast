package fr.podcast.server.html

import fr.podcast.server.auth.UserAccount
import fr.podcast.shared.design.ColorTokens
import fr.podcast.shared.design.DesignTokens
import fr.podcast.shared.design.toCssHex
import fr.podcast.shared.i18n.Language
import fr.podcast.shared.navigation.Destination
import kotlinx.html.BODY
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.MAIN
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.header
import kotlinx.html.lang
import kotlinx.html.li
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.nav
import kotlinx.html.script
import kotlinx.html.span
import kotlinx.html.style
import kotlinx.html.title
import kotlinx.html.ul
import kotlinx.html.unsafe

private const val HTMX_URL = "https://unpkg.com/htmx.org@2.0.11/dist/htmx.min.js"

/**
 * Common page: navigation (when signed in), header, then [content], and the player footer,
 * opened on [player] when the page gives an episode (Home: the one to resume).
 */
fun HTML.page(
    language: Language,
    title: String,
    user: UserAccount?,
    current: Destination? = null,
    content: MAIN.() -> Unit,
    after: BODY.() -> Unit = {},
    player: PlayerStart? = null,
) {
    val strings = language.strings
    lang = language.tag
    head {
        meta(charset = "utf-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1")
        title("${strings.appName} — $title")
        script(src = HTMX_URL) {}
        if (user != null) script(src = "/static/player.js") { attributes["defer"] = "" }
        style { unsafe { +STYLES } }
    }
    body(classes = if (user == null) "anonymous" else null) {
        if (user != null) {
            nav(classes = "nav") {
                // Boosted by htmx: changing page keeps the player (and what it plays).
                attributes["hx-boost"] = "true"
                div("brand") { +strings.appName }
                ul {
                    Destination.entries.forEach { destination ->
                        li {
                            a(href = destination.path) {
                                if (destination == current) attributes["aria-current"] = "page"
                                +strings.destination(destination)
                            }
                        }
                    }
                }
            }
        }
        main {
            header {
                h1 { +title }
                if (user != null) {
                    div("account") {
                        span { +strings.signedInAs(user.displayName) }
                        form(action = "/logout", method = FormMethod.post) {
                            button(classes = "link") { +strings.signOut }
                        }
                    }
                }
            }
            content()
        }
        after()
        if (user != null) playerFooter(strings, player)
    }
}

/** CSS variables generated from the shared design tokens. */
private fun ColorTokens.cssVariables() = listOf(
    "--bg" to background,
    "--surface" to surfaceContainer,
    "--card" to secondaryContainer,
    "--text" to onSurface,
    "--muted" to onSurfaceVariant,
    "--accent" to primary,
    "--error" to error,
).joinToString(" ") { (name, value) -> "$name: ${value.toCssHex()};" }

private val STYLES = """
    :root { ${DesignTokens.light.cssVariables()} color-scheme: light dark; }
    @media (prefers-color-scheme: dark) {
      :root { ${DesignTokens.dark.cssVariables()} }
    }
    * { box-sizing: border-box; }
    body { margin: 0; font-family: system-ui, sans-serif; background: var(--bg); color: var(--text); }
    main { padding: 16px 16px 200px; max-width: 960px; }
    main > header { display: flex; flex-wrap: wrap; align-items: baseline; justify-content: space-between; gap: 8px; }
    h1 { font-size: 1.5rem; margin: 8px 0 16px; }
    h2 { font-size: 1.05rem; margin: 24px 0 12px; }
    ul { list-style: none; margin: 0; padding: 0; }
    a { color: inherit; text-decoration: none; }

    .nav { position: fixed; bottom: 0; left: 0; right: 0; background: var(--surface); z-index: 2; }
    .nav .brand { display: none; }
    .nav ul { display: flex; justify-content: space-around; }
    .nav a { display: block; padding: 14px 8px; color: var(--muted); font-size: .85rem; }
    .nav a[aria-current] { color: var(--text); font-weight: 600; }
    .account { display: flex; align-items: center; gap: 8px; font-size: .85rem; color: var(--muted); }
    .account form { margin: 0; }

    .cards { display: grid; gap: 12px; }
    .card, .episode { display: flex; gap: 12px; align-items: center; }
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
    button.link { background: none; padding: 0; color: var(--accent); text-decoration: underline; }
    .actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 4px; }
    button.action { font-size: .8rem; padding: 4px 12px; }
    .htmx-request.refresh, .htmx-request.action { opacity: .5; }

    .player {
      position: fixed; left: 0; right: 0; bottom: 52px; z-index: 1; display: flex; flex-wrap: wrap; align-items: center;
      gap: 4px 16px; background: var(--surface); padding: 8px 16px; border-top: 3px solid var(--accent);
    }
    .player[hidden] { display: none; }
    .player-info { display: flex; flex-direction: column; min-width: 0; flex: 1 1 200px; }
    .player-info .title { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .player-controls { display: flex; align-items: center; gap: 8px; flex: 2 1 320px; }
    .player audio { flex: 1; min-width: 160px; height: 40px; }
    .player button { padding: 6px 12px; }
    button.play { flex: none; width: 44px; height: 44px; padding: 0; border-radius: 50%; }

    a.button, .upload button, .search button, .add-feed button { display: inline-block; border-radius: 999px; background: var(--card); color: var(--text); padding: 8px 16px; }
    .toolbar { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; margin-bottom: 16px; }
    .upload, .search, .add-feed { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; margin: 0; }
    .upload label { display: flex; gap: 8px; align-items: center; font-size: .9rem; color: var(--muted); }
    .search input, .add-feed input, textarea { font: inherit; padding: 8px 12px; border-radius: 10px; border: 1px solid var(--card); background: var(--surface); color: var(--text); }
    .search input { flex: 1; min-width: 200px; }
    .add-feed input { flex: 1; min-width: 240px; }
    textarea { width: 100%; resize: vertical; }
    .subscription { display: flex; gap: 12px; align-items: flex-start; padding: 12px 0; border-bottom: 1px solid var(--card); }
    .note { display: flex; flex-direction: column; gap: 6px; align-items: flex-start; margin: 4px 0; }
    .toggle { display: flex; gap: 6px; align-items: center; font-size: .85rem; color: var(--muted); }
    .badge { align-self: flex-start; font-size: .75rem; padding: 2px 8px; border-radius: 999px; background: var(--card); }
    .badge.warning, .error { color: var(--error); }
    .subscribed, .notice { font-size: .85rem; color: var(--accent); }
    button.danger { color: var(--error); }
    img.artwork { object-fit: cover; }
    .results .episode { align-items: center; }
    .empty { color: var(--muted); }

    .login { max-width: 360px; margin: 10vh auto 0; display: flex; flex-direction: column; gap: 12px; }
    .login label { display: flex; flex-direction: column; gap: 4px; font-size: .9rem; color: var(--muted); }
    .login input { font: inherit; padding: 10px 12px; border-radius: 10px; border: 1px solid var(--card); background: var(--surface); color: var(--text); }
    .login button { background: var(--accent); color: var(--bg); padding: 10px 16px; }
    .error { font-size: .9rem; margin: 0; }
    body.anonymous main { margin: 0 auto; }

    @media (min-width: 840px) {
      .nav { top: 0; right: auto; width: 240px; padding: 16px 12px; }
      .nav .brand { display: block; font-size: 1.3rem; padding: 8px 16px 16px; }
      .nav ul { flex-direction: column; }
      .nav a { padding: 12px 16px; border-radius: 999px; font-size: 1rem; }
      .nav a[aria-current] { background: var(--card); }
      main { margin-left: 240px; padding: 24px 32px 120px; }
      .cards { grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); }
      .player { left: 240px; bottom: 0; }
    }
""".trimIndent()
