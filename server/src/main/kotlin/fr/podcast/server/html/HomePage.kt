package fr.podcast.server.html

import fr.podcast.server.auth.UserAccount
import fr.podcast.server.hal.Affordance
import fr.podcast.server.hal.Affordances
import fr.podcast.server.media.AudioLinks
import fr.podcast.shared.home.EpisodeItem
import fr.podcast.shared.home.HomeState
import fr.podcast.shared.i18n.Language
import fr.podcast.shared.i18n.Strings
import fr.podcast.shared.model.Episode
import fr.podcast.shared.model.Playlist
import fr.podcast.shared.navigation.Destination
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.HTML
import kotlinx.html.UL
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h2
import kotlinx.html.id
import kotlinx.html.li
import kotlinx.html.option
import kotlinx.html.progress
import kotlinx.html.section
import kotlinx.html.select
import kotlinx.html.span
import kotlinx.html.stream.createHTML
import kotlinx.html.summary
import kotlinx.html.ul
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What Home offers to do with new episodes: recommend them to the server's [others] accounts
 * (ADR 0009), add them to [playlists], the static ones the user may change (ADR 0010).
 */
data class HomeExtras(val others: List<UserAccount> = emptyList(), val playlists: List<Playlist> = emptyList())

/** Home: the player footer opens on the episode to resume (ADR 0008). */
fun HTML.homePage(state: HomeState, queue: List<EpisodeItem>, extras: HomeExtras, language: Language, user: UserAccount, audio: AudioLinks) {
    val strings = language.strings
    page(
        language, strings.destination(Destination.Home), user, Destination.Home,
        content = { homeContent(state, queue, extras, strings, audio) },
        player = state.resume.firstOrNull()?.let { PlayerStart(it, audio.path(it.episode.id)) },
    )
}

/**
 * Home sections. Reloaded by htmx (`/fragments/home`) on "Refresh" and whenever an
 * episode action answers with the `episodes-changed` trigger; the queue also reloads alone.
 */
fun FlowContent.homeContent(state: HomeState, queue: List<EpisodeItem>, extras: HomeExtras, strings: Strings, audio: AudioLinks) {
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
                            playButton(item, strings, audio)
                        }
                    }
                }
            }
        }
        queueSection(queue, strings, audio)
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
                state.latest.forEach { item -> episodeRow(item, extras, strings, audio) }
            }
        }
    }
}

/** The `#home` element alone, for htmx. */
fun homeFragmentHtml(state: HomeState, queue: List<EpisodeItem>, extras: HomeExtras, strings: Strings, audio: AudioLinks): String = buildString {
    createHTML(prettyPrint = false).div { homeContent(state, queue, extras, strings, audio) }.let { wrapped ->
        // kotlinx.html needs a parent element: drop the wrapper, keep #home.
        append(wrapped.removePrefix("<div>").removeSuffix("</div>"))
    }
}

private fun UL.episodeRow(item: EpisodeItem, extras: HomeExtras, strings: Strings, audio: AudioLinks) {
    val episode = item.episode
    li("episode") {
        artwork(item)
        div("card-body") {
            span("overline") { +"${item.podcast.title} · ${strings.shortDate(episode.publishedOn())}" }
            a(href = "/episodes/${episode.id}", classes = "title") { +episode.title }
            span("meta") {
                val details = listOfNotNull(
                    strings.duration(episode.durationSeconds).takeIf { episode.durationSeconds > 0 },
                    strings.status(episode),
                    strings.kept.takeIf { episode.kept },
                ) + strings.marks(episode) + listOfNotNull(strings.fileStatus(episode.fileStatus))
                +details.joinToString(" · ")
            }
            // Same affordances as the HAL-FORMS templates; those without input become buttons.
            div("actions") {
                Affordances.forEpisode(episode).filter { it.properties.isEmpty() }.forEach { affordanceButton(it, strings) }
                enqueueButtons(item, strings)
                ratingSelect(episode, strings)
            }
            if (extras.playlists.isNotEmpty()) addToPlaylistMenu(episode.id, extras.playlists, strings)
            if (extras.others.isNotEmpty()) recommendForm(episode.id, extras.others, strings)
        }
        playButton(item, strings, audio)
    }
}

/** The `rate` template: choosing a rating sends it (`clear-rating` is among the buttons once rated). */
private fun FlowContent.ratingSelect(episode: Episode, strings: Strings) {
    select(classes = "rating") {
        name = "rating"
        attributes["aria-label"] = strings.rating
        attributes["hx-put"] = "/episodes/${episode.id}/rating"
        attributes["hx-swap"] = "none"
        if (episode.rating == null) {
            option {
                value = ""
                selected = true
                disabled = true
                +strings.action("rate")
            }
        }
        (1..5).forEach { stars ->
            option {
                value = stars.toString()
                selected = stars == episode.rating
                +strings.stars(stars)
            }
        }
    }
}

/** The `add-episode` template of each playlist the user may change; the answer appears under it. */
private fun FlowContent.addToPlaylistMenu(episodeId: String, playlists: List<Playlist>, strings: Strings) {
    details("share") {
        summary("action") { +strings.addToPlaylist }
        div("actions") {
            playlists.forEach { playlist ->
                button(classes = "action") {
                    attributes["hx-post"] = "/playlists/${playlist.id}/episodes"
                    attributes["hx-vals"] = buildJsonObject { put("episodeId", episodeId) }.toString()
                    attributes["hx-target"] = "#added-$episodeId"
                    +playlist.title
                }
            }
        }
        result("added-$episodeId")
    }
}

/** The `recommend` template: to whom, and a word; the answer appears under the form. */
private fun FlowContent.recommendForm(episodeId: String, others: List<UserAccount>, strings: Strings) {
    details("share") {
        summary("action") { +strings.recommend }
        form(classes = "fields") {
            attributes["hx-post"] = "/episodes/$episodeId/shares"
            attributes["hx-target"] = "#recommend-$episodeId"
            attributes["hx-on::after-request"] = RESET_WHEN_ACCEPTED
            recipientSelect(others, strings)
            messageInput(strings)
            button(type = ButtonType.submit) { +strings.recommend }
        }
        result("recommend-$episodeId")
    }
}

private fun FlowContent.affordanceButton(affordance: Affordance, strings: Strings) {
    button(classes = "action") {
        attributes["hx-${affordance.method.value.lowercase()}"] = affordance.target
        attributes["hx-swap"] = "none"
        +strings.action(affordance.name)
    }
}

