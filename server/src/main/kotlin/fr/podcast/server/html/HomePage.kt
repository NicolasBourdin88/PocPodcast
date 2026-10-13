package fr.podcast.server.html

import fr.podcast.server.auth.UserAccount
import fr.podcast.server.hal.Affordance
import fr.podcast.server.hal.Affordances
import fr.podcast.shared.design.artworkColor
import fr.podcast.shared.design.initials
import fr.podcast.shared.design.toCssHex
import fr.podcast.shared.home.EpisodeItem
import fr.podcast.shared.home.HomeState
import fr.podcast.shared.i18n.Language
import fr.podcast.shared.i18n.Strings
import fr.podcast.shared.navigation.Destination
import kotlinx.html.FlowContent
import kotlinx.html.HTML
import kotlinx.html.UL
import kotlinx.html.a
import kotlinx.html.aside
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.footer
import kotlinx.html.h2
import kotlinx.html.id
import kotlinx.html.li
import kotlinx.html.progress
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.stream.createHTML
import kotlinx.html.ul

fun HTML.homePage(state: HomeState, language: Language, user: UserAccount) {
    val strings = language.strings
    page(
        language, strings.destination(Destination.Home), user, Destination.Home,
        content = { homeContent(state, strings) },
        after = {
            state.resume.firstOrNull()?.let { item ->
                footer("mini-player") {
                    artwork(item)
                    div("card-body") {
                        span("title") { +item.episode.title }
                        span("meta") { +item.podcast.title }
                    }
                    button(classes = "play") { attributes["aria-label"] = strings.play; +"▶" }
                }
            }
        },
    )
}

/**
 * Home sections. Reloaded by htmx (`/fragments/home`) on "Refresh" and whenever an
 * episode action answers with the `episodes-changed` trigger.
 */
fun FlowContent.homeContent(state: HomeState, strings: Strings) {
    div {
        id = "home"
        attributes["hx-get"] = "/fragments/home"
        attributes["hx-trigger"] = "episodes-changed from:body"
        attributes["hx-swap"] = "outerHTML"

        if (state.resume.isNotEmpty()) {
            section {
                h2 { +strings.resumeListening }
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
                                span("meta") { +strings.remaining(item.episode.remainingMinutes) }
                            }
                        }
                    }
                }
            }
        }
        section {
            div("section-header") {
                h2 { +strings.latestEpisodes }
                button(classes = "refresh") {
                    attributes["hx-get"] = "/fragments/home"
                    attributes["hx-target"] = "#home"
                    attributes["hx-swap"] = "outerHTML"
                    +strings.refresh
                }
            }
            ul("episodes") {
                id = "latest"
                state.latest.forEach { item -> episodeRow(item, strings) }
            }
        }
    }
}

/** The `#home` element alone, for htmx. */
fun homeFragmentHtml(state: HomeState, strings: Strings): String = buildString {
    createHTML(prettyPrint = false).div { homeContent(state, strings) }.let { wrapped ->
        // kotlinx.html needs a parent element: drop the wrapper, keep #home.
        append(wrapped.removePrefix("<div>").removeSuffix("</div>"))
    }
}

private fun UL.episodeRow(item: EpisodeItem, strings: Strings) {
    val episode = item.episode
    li("episode") {
        artwork(item)
        div("card-body") {
            span("overline") { +"${item.podcast.title} · ${strings.shortDate(episode.publishedOn())}" }
            a(href = "/episodes/${episode.id}", classes = "title") { +episode.title }
            span("meta") {
                +listOfNotNull(
                    strings.duration(episode.durationSeconds).takeIf { episode.durationSeconds > 0 },
                    strings.status(episode),
                    strings.kept.takeIf { episode.kept },
                    strings.fileStatus(episode.fileStatus),
                ).joinToString(" · ")
            }
            // Same affordances as the HAL-FORMS templates; those without input become buttons.
            div("actions") {
                Affordances.forEpisode(episode).filter { it.properties.isEmpty() }.forEach { affordanceButton(it, strings) }
            }
        }
    }
}

private fun FlowContent.affordanceButton(affordance: Affordance, strings: Strings) {
    button(classes = "action") {
        attributes["hx-${affordance.method.value.lowercase()}"] = affordance.target
        attributes["hx-swap"] = "none"
        +strings.action(affordance.name)
    }
}

private fun FlowContent.artwork(item: EpisodeItem) {
    aside("artwork") {
        attributes["style"] = "background: ${item.podcast.artworkColor.toCssHex()}"
        attributes["aria-hidden"] = "true"
        +item.podcast.initials
    }
}
