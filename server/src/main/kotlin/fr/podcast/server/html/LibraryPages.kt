package fr.podcast.server.html

import fr.podcast.server.auth.UserAccount
import fr.podcast.server.search.SearchHit
import fr.podcast.server.search.normalizedFeed
import fr.podcast.shared.design.artworkColor
import fr.podcast.shared.design.initials
import fr.podcast.shared.design.toCssHex
import fr.podcast.shared.i18n.Language
import fr.podcast.shared.i18n.Strings
import fr.podcast.shared.model.OpmlImportReport
import fr.podcast.shared.model.Podcast
import fr.podcast.shared.model.RefreshStatus
import fr.podcast.shared.model.SubscriptionItem
import fr.podcast.shared.navigation.Destination
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.aside
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h2
import kotlinx.html.id
import kotlinx.html.img
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.stream.createHTML
import kotlinx.html.textArea
import kotlinx.html.ul
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Library (GET /subscriptions in HTML): annotations, automatic download, OPML. */
fun HTML.libraryPage(items: List<SubscriptionItem>, language: Language, user: UserAccount) {
    val strings = language.strings
    page(language, strings.destination(Destination.Library), user, Destination.Library, content = {
        section("toolbar") {
            a(href = "/subscriptions/opml", classes = "button") {
                attributes["download"] = "podcasts.opml"
                +strings.opmlExport
            }
            form(classes = "upload") {
                attributes["hx-post"] = "/subscriptions/opml"
                attributes["hx-encoding"] = "multipart/form-data"
                attributes["hx-target"] = "#opml-result"
                label {
                    +strings.opmlImport
                    input(type = InputType.file, name = "opml") {
                        accept = ".opml,.xml,text/x-opml,application/xml"
                        required = true
                    }
                }
                button(type = ButtonType.submit) { +strings.opmlImport }
            }
            p { id = "opml-result" }
        }
        div {
            id = "library"
            attributes["hx-get"] = "/subscriptions"
            attributes["hx-select"] = "#library"
            attributes["hx-trigger"] = "subscriptions-changed from:body"
            attributes["hx-swap"] = "outerHTML"
            if (items.isEmpty()) {
                p("empty") { +strings.emptyLibrary }
            }
            ul("subscriptions") {
                items.forEach { item -> subscriptionRow(item, strings) }
            }
        }
    })
}

private fun kotlinx.html.UL.subscriptionRow(item: SubscriptionItem, strings: Strings) {
    val subscription = item.subscription
    val podcast = item.podcast
    li("subscription") {
        artwork(podcast)
        div("card-body") {
            span("title") { +podcast.title }
            span("meta") { +podcast.author }
            when (podcast.refreshStatus) {
                RefreshStatus.PENDING -> span("badge") { +strings.refreshPending }
                RefreshStatus.FAILED -> span("badge warning") { +strings.refreshFailed }
                RefreshStatus.OK -> Unit
            }
            // PATCH from a form: absent fields keep their value.
            form(classes = "note") {
                attributes["hx-patch"] = "/subscriptions/${subscription.id}"
                attributes["hx-swap"] = "none"
                textArea(rows = "2") {
                    name = "note"
                    placeholder = strings.notePlaceholder
                    attributes["aria-label"] = strings.note
                    +(subscription.note ?: "")
                }
                button(type = ButtonType.submit, classes = "action") { +strings.save }
            }
            div("actions") {
                label("toggle") {
                    input(type = InputType.checkBox) {
                        checked = subscription.autoDownload
                        attributes["hx-patch"] = "/subscriptions/${subscription.id}"
                        attributes["hx-vals"] = buildJsonObject { put("autoDownload", !subscription.autoDownload) }.toString()
                        attributes["hx-swap"] = "none"
                    }
                    +strings.autoDownload
                }
                button(classes = "action danger") {
                    attributes["hx-delete"] = "/subscriptions/${subscription.id}"
                    attributes["hx-confirm"] = strings.unsubscribeConfirm(podcast.title)
                    attributes["hx-swap"] = "none"
                    +strings.unsubscribe
                }
            }
        }
    }
}

/**
 * Explore (GET /search in HTML): directory search and subscription by feed address.
 * [subscribed] holds the user's feeds, normalized (see normalizedFeed).
 */
fun HTML.explorePage(query: String, hits: List<SearchHit>?, subscribed: Set<String>, language: Language, user: UserAccount, error: String? = null) {
    val strings = language.strings
    page(language, strings.destination(Destination.Explore), user, Destination.Explore, content = {
        form(action = "/search", method = FormMethod.get, classes = "search") {
            input(type = InputType.search, name = "q") {
                value = query
                placeholder = strings.searchPlaceholder
                attributes["aria-label"] = strings.searchPlaceholder
                attributes["minlength"] = "2"
                required = true
            }
            button(type = ButtonType.submit) { +strings.search }
        }
        if (error != null) p("error") { +error }
        if (hits != null) {
            if (hits.isEmpty()) p("empty") { +strings.noResults(query) }
            ul("results") {
                hits.forEach { hit ->
                    li("episode") {
                        hitArtwork(hit)
                        div("card-body") {
                            span("overline") { +hit.source }
                            span("title") { +hit.title }
                            span("meta") { +hit.author }
                        }
                        if (normalizedFeed(hit.feedUrl) in subscribed) {
                            span("subscribed") { +strings.subscribed }
                        } else {
                            subscribeButton(hit.feedUrl, strings)
                        }
                    }
                }
            }
        }
        section {
            h2 { +strings.addFeedTitle }
            form(classes = "add-feed") {
                attributes["hx-post"] = "/subscriptions"
                attributes["hx-target"] = "#add-result"
                input(type = InputType.url, name = "feedUrl") {
                    placeholder = "https://…/feed.xml"
                    attributes["aria-label"] = strings.feedUrl
                    required = true
                }
                button(type = ButtonType.submit) { +strings.subscribe }
            }
            p { id = "add-result" }
        }
    })
}

/** Replaced by the server's answer: "Subscribed" or why the feed was refused. */
private fun FlowContent.subscribeButton(feedUrl: String, strings: Strings) {
    button(classes = "action") {
        attributes["hx-post"] = "/subscriptions"
        attributes["hx-vals"] = buildJsonObject { put("feedUrl", feedUrl) }.toString()
        attributes["hx-swap"] = "outerHTML"
        +strings.subscribe
    }
}

/** htmx answers (always 200: htmx only swaps successful responses). */
fun subscribedFragment(podcast: Podcast, strings: Strings): String =
    createHTML(prettyPrint = false).span("subscribed") { +strings.subscribedTo(podcast.title) }

fun refusedFragment(message: String): String = createHTML(prettyPrint = false).span("error") { +message }

fun opmlReportFragment(report: OpmlImportReport, strings: Strings): String = createHTML(prettyPrint = false).span("notice") {
    +strings.opmlReport(report.imported, report.alreadySubscribed, report.failed.size)
}

private fun FlowContent.artwork(podcast: Podcast) {
    val image = podcast.artworkUrl
    if (image != null) {
        img(src = image, alt = "", classes = "artwork") { attributes["loading"] = "lazy" }
    } else {
        aside("artwork") {
            attributes["style"] = "background: ${podcast.artworkColor.toCssHex()}"
            attributes["aria-hidden"] = "true"
            +podcast.initials
        }
    }
}

private fun FlowContent.hitArtwork(hit: SearchHit) {
    val image = hit.artworkUrl
    if (image != null) {
        img(src = image, alt = "", classes = "artwork") { attributes["loading"] = "lazy" }
    } else {
        aside("artwork") { +hit.title.take(2).uppercase() }
    }
}
