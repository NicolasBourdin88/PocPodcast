package fr.podcast.server.html

import io.ktor.http.encodeURLParameter
import fr.podcast.server.auth.UserAccount
import fr.podcast.server.catalog.ShareRecord
import fr.podcast.server.catalog.SharedEntry
import fr.podcast.server.media.AudioLinks
import fr.podcast.server.search.SearchHit
import fr.podcast.server.search.normalizedFeed
import fr.podcast.shared.i18n.Language
import fr.podcast.shared.home.EpisodeItem
import fr.podcast.shared.i18n.Strings
import fr.podcast.shared.model.Access
import fr.podcast.shared.model.OpmlImportReport
import fr.podcast.shared.model.Podcast
import fr.podcast.shared.model.RefreshStatus
import fr.podcast.shared.model.SharePermission
import fr.podcast.shared.model.ShareRules
import fr.podcast.shared.model.Subscription
import fr.podcast.shared.model.SubscriptionItem
import fr.podcast.shared.navigation.Destination
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.SELECT
import kotlinx.html.UL
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.aside
import kotlinx.html.button
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h2
import kotlinx.html.id
import kotlinx.html.img
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.li
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.q
import kotlinx.html.section
import kotlinx.html.select
import kotlinx.html.span
import kotlinx.html.stream.createHTML
import kotlinx.html.summary
import kotlinx.html.textArea
import kotlinx.html.ul
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The library filters (ADR 0012): words, a tag, and the user's tags to choose from. */
data class LibraryFilter(val text: String = "", val tag: String = "", val tags: List<String> = emptyList()) {
    val active: Boolean get() = text.isNotBlank() || tag.isNotEmpty()

    /** The page with these filters, which the list reloads from. */
    val path: String
        get() = buildList {
            if (text.isNotEmpty()) add("q=${text.encodeURLParameter()}")
            if (tag.isNotEmpty()) add("tag=${tag.encodeURLParameter()}")
        }.let { if (it.isEmpty()) "/subscriptions" else "/subscriptions?" + it.joinToString("&") }
}

/** What the library page shows besides the subscriptions (ADR 0009): shares given and received, and whom one can share with. */
data class LibraryView(
    val items: List<SubscriptionItem>,
    val filter: LibraryFilter = LibraryFilter(),
    /** Shares of the user's subscriptions, by subscription id. */
    val given: Map<Long, List<ShareRecord>> = emptyMap(),
    val received: List<SharedEntry> = emptyList(),
    /** The server's other accounts. */
    val others: List<UserAccount> = emptyList(),
)

/**
 * Library (GET /subscriptions in HTML): annotations, automatic download, OPML; whom each
 * subscription is shared with, and what others share with the user (ADR 0009).
 */
fun HTML.libraryPage(library: LibraryView, language: Language, user: UserAccount, audio: AudioLinks) {
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
            id = "library-filters"
            // The tags to choose from change with the subscriptions.
            reloadOn(library.filter.path, "subscriptions-changed")
            libraryFilters(library.filter, strings)
        }
        // Outside the list, which reloads after each change: the answer to a share stays visible.
        result("library-notice")
        div {
            id = "library"
            // With its filters, kept after each change.
            reloadOn(library.filter.path, "subscriptions-changed", "shares-changed")
            if (library.items.isEmpty()) {
                p("empty") { +if (library.filter.active) strings.noSubscriptionFound else strings.emptyLibrary }
            }
            ul("subscriptions") {
                library.items.forEach { item ->
                    subscriptionRow(item, library.given[item.subscription.id.toLong()].orEmpty(), library.others, strings)
                }
            }
            if (library.received.isNotEmpty()) sharedWithMe(library, strings, audio)
        }
    })
}

private fun UL.subscriptionRow(item: SubscriptionItem, shares: List<ShareRecord>, others: List<UserAccount>, strings: Strings) {
    val subscription = item.subscription
    val podcast = item.podcast
    li("subscription") {
        artwork(podcast)
        div("card-body") {
            span("title") { +podcast.title }
            span("meta") { +podcast.author }
            if (subscription.tags.isNotEmpty()) {
                span("tags") {
                    subscription.tags.forEach { tag ->
                        a(href = LibraryFilter(tag = tag).path, classes = "tag") {
                            attributes["hx-boost"] = "true"
                            +tag
                        }
                    }
                }
            }
            when (podcast.refreshStatus) {
                RefreshStatus.PENDING -> span("badge") { +strings.refreshPending }
                RefreshStatus.FAILED -> span("badge warning") { +strings.refreshFailed }
                RefreshStatus.OK -> Unit
            }
            noteForm(subscription, strings)
            div("actions") {
                autoDownloadToggle(subscription, strings)
                button(classes = "action danger") {
                    attributes["hx-delete"] = "/subscriptions/${subscription.id}"
                    attributes["hx-confirm"] = strings.unsubscribeConfirm(podcast.title)
                    attributes["hx-swap"] = "none"
                    +strings.unsubscribe
                }
            }
            if (shares.isNotEmpty()) sharedWith(shares, strings, "#library-notice")
            if (others.isNotEmpty()) shareForm("/subscriptions/${subscription.id}/shares", others, strings, "#library-notice")
        }
    }
}

/** PATCH from a form: absent fields keep their value. */
private fun FlowContent.noteForm(subscription: Subscription, strings: Strings) {
    form(classes = "note") {
        attributes["hx-patch"] = "/subscriptions/${subscription.id}"
        attributes["hx-swap"] = "none"
        textArea(rows = "2") {
            name = "note"
            placeholder = strings.notePlaceholder
            attributes["aria-label"] = strings.note
            +(subscription.note ?: "")
        }
        input(type = InputType.text, name = "tags") {
            value = subscription.tags.joinToString(", ")
            placeholder = strings.tagsHint
            attributes["aria-label"] = strings.tags
        }
        button(type = ButtonType.submit, classes = "action") { +strings.save }
    }
}

private fun FlowContent.autoDownloadToggle(subscription: Subscription, strings: Strings) {
    label("toggle") {
        input(type = InputType.checkBox) {
            checked = subscription.autoDownload
            attributes["hx-patch"] = "/subscriptions/${subscription.id}"
            attributes["hx-vals"] = buildJsonObject { put("autoDownload", !subscription.autoDownload) }.toString()
            attributes["hx-swap"] = "none"
        }
        +strings.autoDownload
    }
}

/**
 * Whom the owner shares a subscription or a playlist with: what each may do (`change-permission`), or
 * stop (`unshare`); answers appear in [notice].
 */
internal fun FlowContent.sharedWith(shares: List<ShareRecord>, strings: Strings, notice: String) {
    div("shared-with") {
        span("meta") { +strings.sharedWith }
        ul {
            shares.forEach { record ->
                val share = record.share
                li {
                    span { +share.grantee.displayName }
                    select {
                        name = "permission"
                        attributes["aria-label"] = strings.sharePermission
                        attributes["hx-patch"] = "/shares/${share.id}"
                        attributes["hx-target"] = notice
                        permissionOptions(share.permission, strings)
                    }
                    button(classes = "link danger") {
                        attributes["hx-delete"] = "/shares/${share.id}"
                        attributes["hx-swap"] = "none"
                        +strings.stopSharing
                    }
                }
            }
        }
    }
}

/** The `share` template, posted to [target]: with whom, what they may do, and a word; the answer appears in [notice]. */
internal fun FlowContent.shareForm(target: String, others: List<UserAccount>, strings: Strings, notice: String) {
    details("share") {
        summary("action") { +strings.share }
        form(classes = "fields") {
            attributes["hx-post"] = target
            attributes["hx-target"] = notice
            attributes["hx-on::after-request"] = RESET_WHEN_ACCEPTED
            recipientSelect(others, strings)
            label {
                +strings.sharePermission
                select {
                    name = "permission"
                    permissionOptions(SharePermission.READ, strings)
                }
            }
            messageInput(strings)
            button(type = ButtonType.submit) { +strings.share }
        }
    }
}

internal fun SELECT.permissionOptions(current: SharePermission, strings: Strings) {
    SharePermission.entries.forEach { permission ->
        option {
            value = permission.name
            selected = permission == current
            +strings.permission(permission)
        }
    }
}

/** Whom to share with: the server's other accounts. */
internal fun FlowContent.recipientSelect(others: List<UserAccount>, strings: Strings) {
    label {
        +strings.shareWith
        select {
            name = "userId"
            required = true
            option {
                value = ""
                +"—"
            }
            others.forEach { other ->
                option {
                    value = other.id.toString()
                    +other.displayName
                }
            }
        }
    }
}

internal fun FlowContent.messageInput(strings: Strings) {
    label {
        +strings.shareMessage
        textArea(rows = "2") {
            name = "message"
            maxLength = ShareRules.MAX_MESSAGE_LENGTH.toString()
        }
    }
}

/** What others share with the user: subscriptions (editable when shared WRITE) and recommended episodes. */
private fun FlowContent.sharedWithMe(library: LibraryView, strings: Strings, audio: AudioLinks) {
    val followed = library.items.map { it.podcast.id }.toSet()
    section("shared") {
        h2 { +strings.sharedWithMe }
        ul("subscriptions") {
            library.received.forEach { entry ->
                entry.subscription?.let { sharedSubscriptionRow(entry.record, it, followed = it.podcast.id in followed, strings) }
                entry.episode?.let { recommendationRow(entry.record, it, strings, audio) }
            }
        }
    }
}

private fun UL.sharedSubscriptionRow(record: ShareRecord, item: SubscriptionItem, followed: Boolean, strings: Strings) {
    val subscription = item.subscription
    val podcast = item.podcast
    val owner = record.share.owner.displayName
    val writable = subscription.access == Access.WRITE
    li("subscription") {
        artwork(podcast)
        div("card-body") {
            span("overline") { +"${strings.sharedBy(owner)} · ${strings.permission(if (writable) SharePermission.WRITE else SharePermission.READ)}" }
            span("title") { +podcast.title }
            span("meta") { +podcast.author }
            record.share.message?.let { q("message") { +it } }
            if (writable) {
                noteForm(subscription, strings)
            } else {
                subscription.note?.takeIf { it.isNotBlank() }?.let { note -> p("meta") { +"${strings.ownerNote(owner)} — $note" } }
            }
            div("actions") {
                if (writable) autoDownloadToggle(subscription, strings)
                // To follow it oneself; the share stays until it is removed.
                if (followed) span("subscribed") { +strings.subscribed } else subscribeButton(podcast.feedUrl, strings)
                declineButton(record, strings)
            }
        }
    }
}

private fun UL.recommendationRow(record: ShareRecord, item: EpisodeItem, strings: Strings, audio: AudioLinks) {
    li("episode") {
        artwork(item)
        div("card-body") {
            span("overline") { +"${strings.recommendedBy(record.share.owner.displayName)} · ${item.podcast.title}" }
            span("title") { +item.episode.title }
            record.share.message?.let { q("message") { +it } }
            div("actions") { declineButton(record, strings) }
        }
        playButton(item, strings, audio)
    }
}

/** The grantee removes what was shared with them (`unshare`). */
private fun FlowContent.declineButton(record: ShareRecord, strings: Strings) {
    button(classes = "action") {
        attributes["hx-delete"] = "/shares/${record.share.id}"
        attributes["hx-swap"] = "none"
        +strings.decline
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
            // Boosted: searching keeps the player going.
            attributes["hx-boost"] = "true"
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

fun opmlReportFragment(report: OpmlImportReport, strings: Strings): String =
    noticeFragment(strings.opmlReport(report.imported, report.alreadySubscribed, report.failed.size))

/** What was done, in a sentence. */
fun noticeFragment(message: String): String = createHTML(prettyPrint = false).span("notice") { +message }

private fun FlowContent.hitArtwork(hit: SearchHit) {
    val image = hit.artworkUrl
    if (image != null) {
        img(src = image, alt = "", classes = "artwork") { attributes["loading"] = "lazy" }
    } else {
        aside("artwork") { +hit.title.take(2).uppercase() }
    }
}

/** Words and a tag; boosted, so that the player keeps going. Also the way to the episode search. */
private fun FlowContent.libraryFilters(filter: LibraryFilter, strings: Strings) {
    form(action = "/subscriptions", method = FormMethod.get, classes = "search") {
        attributes["hx-boost"] = "true"
        input(type = InputType.search, name = "q") {
            value = filter.text
            placeholder = strings.filterLibrary
            attributes["aria-label"] = strings.filterLibrary
        }
        if (filter.tags.isNotEmpty()) {
            select {
                name = "tag"
                attributes["aria-label"] = strings.tags
                option {
                    value = ""
                    +strings.allTags
                }
                filter.tags.forEach { tag ->
                    option {
                        value = tag
                        selected = tag.equals(filter.tag, ignoreCase = true)
                        +tag
                    }
                }
            }
        }
        button(type = ButtonType.submit) { +strings.search }
        a(href = "/episodes", classes = "button") {
            attributes["hx-boost"] = "true"
            +strings.searchEpisodes
        }
    }
}
