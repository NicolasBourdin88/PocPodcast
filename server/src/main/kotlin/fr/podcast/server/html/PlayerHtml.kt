package fr.podcast.server.html

import fr.podcast.server.media.AudioLinks
import fr.podcast.shared.design.artworkColor
import fr.podcast.shared.design.initials
import fr.podcast.shared.design.toCssHex
import fr.podcast.shared.home.EpisodeItem
import fr.podcast.shared.i18n.Strings
import fr.podcast.shared.model.EpisodeStatus
import kotlinx.html.BODY
import kotlinx.html.FlowContent
import kotlinx.html.HTMLTag
import kotlinx.html.aside
import kotlinx.html.audio
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.footer
import kotlinx.html.h2
import kotlinx.html.id
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.stream.createHTML
import kotlinx.html.ul
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The episode the player opens with: Home gives the one to resume. */
data class PlayerStart(val item: EpisodeItem, val audioPath: String)

private const val SKIP_BACK = 15
private const val SKIP_FORWARD = 30

/**
 * The player of the thin client (static/player.js): one `<audio>` kept from page to page, as the
 * footer has hx-preserve and navigation is boosted by htmx. Hidden until there is an episode.
 */
fun BODY.playerFooter(strings: Strings, start: PlayerStart?) {
    footer("player") {
        id = "player"
        attributes["hx-preserve"] = "true"
        if (start == null) {
            attributes["hidden"] = ""
        } else {
            episodeData(start.item, start.audioPath, attribute = "data-episode")
        }
        div("player-info") {
            span("title") {
                id = "player-title"
                +(start?.item?.episode?.title ?: "")
            }
            span("meta") {
                id = "player-podcast"
                +(start?.item?.podcast?.title ?: "")
            }
        }
        div("player-controls") {
            button(classes = "skip") {
                attributes["data-skip"] = "-$SKIP_BACK"
                attributes["aria-label"] = strings.skipBack(SKIP_BACK)
                +"−$SKIP_BACK"
            }
            audio {
                id = "audio"
                attributes["controls"] = ""
                attributes["preload"] = "none"
            }
            button(classes = "skip") {
                attributes["data-skip"] = "$SKIP_FORWARD"
                attributes["aria-label"] = strings.skipForward(SKIP_FORWARD)
                +"+$SKIP_FORWARD"
            }
            button(classes = "speed") {
                attributes["data-speed"] = ""
                +strings.speed(1f)
            }
            button(classes = "next") {
                attributes["data-next"] = ""
                attributes["aria-label"] = strings.next
                attributes["title"] = strings.next
                +"⏭"
            }
        }
    }
}

/** Plays the episode in the player footer (or pauses it, when it is the one playing). */
fun FlowContent.playButton(item: EpisodeItem, strings: Strings, audio: AudioLinks) {
    button(classes = "play") {
        episodeData(item, audio.path(item.episode.id), attribute = "data-play")
        attributes["aria-label"] = strings.play
        attributes["title"] = strings.play
        +"▶"
    }
}

/** What player.js needs to open an episode without asking the server. */
private fun HTMLTag.episodeData(item: EpisodeItem, audioPath: String, attribute: String) {
    val episode = item.episode
    attributes[attribute] = episode.id
    attributes["data-audio"] = audioPath
    attributes["data-title"] = episode.title
    attributes["data-podcast"] = item.podcast.title
    // A played episode starts again from the beginning, as in the apps.
    attributes["data-position"] = (if (episode.status == EpisodeStatus.PLAYED) 0 else episode.positionSeconds).toString()
}

/**
 * "Add to the queue" and "Play next": the `enqueue` template, with and without a position.
 * Removing from the queue is the `dequeue` template, shown with the other episode actions.
 */
fun FlowContent.enqueueButtons(item: EpisodeItem, strings: Strings) {
    if (item.episode.inQueue) return
    val episodeId = item.episode.id
    button(classes = "action") {
        attributes["hx-post"] = "/queue"
        attributes["hx-vals"] = buildJsonObject { put("episodeId", episodeId) }.toString()
        attributes["hx-swap"] = "none"
        +strings.addToQueue
    }
    button(classes = "action") {
        attributes["hx-post"] = "/queue"
        attributes["hx-vals"] = buildJsonObject { put("episodeId", episodeId); put("position", 0) }.toString()
        attributes["hx-swap"] = "none"
        +strings.playNext
    }
}

/** The queue on Home; reloaded by htmx whenever it changes (`queue-changed`). */
fun FlowContent.queueSection(queue: List<EpisodeItem>, strings: Strings, audio: AudioLinks) {
    section {
        id = "queue"
        attributes["hx-get"] = "/fragments/queue"
        attributes["hx-trigger"] = "queue-changed from:body"
        attributes["hx-swap"] = "outerHTML"
        div("section-header") {
            h2 { +strings.queue }
            if (queue.isNotEmpty()) {
                button(classes = "action") {
                    attributes["hx-delete"] = "/queue"
                    attributes["hx-swap"] = "none"
                    +strings.clearQueue
                }
            }
        }
        if (queue.isEmpty()) p("empty") { +strings.emptyQueue }
        ul("episodes") {
            queue.forEachIndexed { index, item ->
                li("episode") {
                    artwork(item)
                    div("card-body") {
                        span("title") { +item.episode.title }
                        span("meta") { +"${item.podcast.title} · ${strings.duration(item.episode.durationSeconds)}" }
                        div("actions") {
                            if (index > 0) {
                                button(classes = "action") {
                                    attributes["hx-put"] = "/queue/${item.episode.id}"
                                    attributes["hx-vals"] = buildJsonObject { put("position", index - 1) }.toString()
                                    attributes["hx-swap"] = "none"
                                    +strings.moveUp
                                }
                            }
                            button(classes = "action") {
                                attributes["hx-delete"] = "/queue/${item.episode.id}"
                                attributes["hx-swap"] = "none"
                                +strings.removeFromQueue
                            }
                        }
                    }
                    playButton(item, strings, audio)
                }
            }
        }
    }
}

fun queueFragmentHtml(queue: List<EpisodeItem>, strings: Strings, audio: AudioLinks): String =
    createHTML(prettyPrint = false).div { queueSection(queue, strings, audio) }.removePrefix("<div>").removeSuffix("</div>")

internal fun FlowContent.artwork(item: EpisodeItem) {
    aside("artwork") {
        attributes["style"] = "background: ${item.podcast.artworkColor.toCssHex()}"
        attributes["aria-hidden"] = "true"
        +item.podcast.initials
    }
}
