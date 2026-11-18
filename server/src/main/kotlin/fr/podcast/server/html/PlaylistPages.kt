package fr.podcast.server.html

import fr.podcast.server.auth.UserAccount
import fr.podcast.server.catalog.PlaylistView
import fr.podcast.server.catalog.ShareRecord
import fr.podcast.server.catalog.SharedEntry
import fr.podcast.server.media.AudioLinks
import fr.podcast.shared.home.EpisodeItem
import fr.podcast.shared.i18n.Language
import fr.podcast.shared.i18n.Strings
import fr.podcast.shared.model.Access
import fr.podcast.shared.model.Playlist
import fr.podcast.shared.model.PlaylistKind
import fr.podcast.shared.model.PlaylistLimits
import fr.podcast.shared.model.PlaylistOrder
import fr.podcast.shared.model.PlaylistRules
import fr.podcast.shared.model.SharePermission
import fr.podcast.shared.navigation.Destination
import kotlinx.html.ButtonType
import kotlinx.html.FORM
import kotlinx.html.FlowContent
import kotlinx.html.HTML
import kotlinx.html.InputType
import kotlinx.html.UL
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.fieldSet
import kotlinx.html.form
import kotlinx.html.h2
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.legend
import kotlinx.html.li
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.q
import kotlinx.html.section
import kotlinx.html.select
import kotlinx.html.span
import kotlinx.html.summary
import kotlinx.html.textArea
import kotlinx.html.ul
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Playlists (GET /playlists in HTML, ADR 0010): the user's own, those shared with them ([shared]),
 * and a form to create one, static or dynamic.
 */
fun HTML.playlistsPage(own: List<PlaylistView>, shared: List<SharedEntry>, language: Language, user: UserAccount) {
    val strings = language.strings
    page(language, strings.destination(Destination.Playlists), user, Destination.Playlists, content = {
        // Outside the list, which reloads after each change: refusals stay visible.
        result("playlists-notice")
        div {
            id = "playlists"
            reloadOn("/playlists", "playlists-changed", "shares-changed")
            // Boosted: opening a playlist keeps the player going.
            attributes["hx-boost"] = "true"
            if (own.isEmpty()) p("empty") { +strings.emptyPlaylists }
            ul("rows") { own.forEach { playlistRow(it.playlist, strings) } }
            if (shared.isNotEmpty()) {
                h2 { +strings.sharedPlaylists }
                ul("rows") { shared.forEach { entry -> playlistRow(entry.playlist!!.playlist, strings, entry.record.share.message) } }
            }
        }
        details("add") {
            summary("action") { +strings.newPlaylist }
            form(classes = "fields") {
                attributes["hx-post"] = "/playlists"
                attributes["hx-target"] = "#playlists-notice"
                titleFields(null, strings)
                fieldSet("choices") {
                    PlaylistKind.entries.forEach { kind ->
                        label("toggle") {
                            input(type = InputType.radio, name = "kind") {
                                value = kind.name
                                checked = kind == PlaylistKind.STATIC
                            }
                            +"${strings.playlistKind(kind)} — ${strings.playlistKindHint(kind)}"
                        }
                    }
                }
                fieldSet("rules") {
                    legend { +strings.playlistKind(PlaylistKind.DYNAMIC) }
                    rulesFields(PlaylistRules(), strings)
                }
                button(type = ButtonType.submit) { +strings.create }
            }
        }
    })
}

/** What a playlist's page shows, as its reader sees it. */
data class PlaylistPage(
    val view: PlaylistView,
    val episodes: List<EpisodeItem>,
    val canModify: Boolean,
    val owner: Boolean,
    /** For the owner: whom it is shared with, and whom it could be. */
    val shares: List<ShareRecord> = emptyList(),
    val others: List<UserAccount> = emptyList(),
    /** For the owner: the absolute address of its RSS feed, once published. */
    val feedUrl: String? = null,
)

/**
 * A playlist (GET /playlists/{id} in HTML): its episodes, and what the reader's rights allow (ADR 0010):
 * anyone plays it; whoever may modify it edits it, shuffles a random one and changes a static one's
 * episodes; its owner shares it, publishes its RSS feed and deletes it.
 */
fun HTML.playlistPage(data: PlaylistPage, language: Language, user: UserAccount, audio: AudioLinks) {
    val strings = language.strings
    val playlist = data.view.playlist
    val editable = data.canModify && playlist.kind == PlaylistKind.STATIC
    page(language, playlist.title, user, Destination.Playlists, content = {
        result("playlist-notice")
        div {
            id = "playlist"
            reloadOn("/playlists/${playlist.id}", "playlists-changed", "shares-changed")
            p("meta") { +"${strings.playlistKind(playlist.kind)} · ${strings.episodeCount(playlist.episodeCount)}" }
            playlist.description?.let { p { +it } }
            playlist.rules?.let { p("meta") { +strings.playlistRules(it) } }
            playlist.ownerName?.let { owner ->
                val permission = if (playlist.access == Access.WRITE) SharePermission.WRITE else SharePermission.READ
                p("meta") { +"${strings.playlistSharedBy(owner)} · ${strings.permission(permission)}" }
            }
            div("actions") {
                if (data.episodes.isNotEmpty()) playAllButton(data.episodes, strings, audio)
                if (data.canModify && playlist.rules?.order == PlaylistOrder.RANDOM) {
                    button(classes = "action") {
                        attributes["hx-post"] = "/playlists/${playlist.id}/shuffle"
                        attributes["hx-swap"] = "none"
                        +strings.shuffle
                    }
                }
                if (data.owner) {
                    button(classes = "action danger") {
                        attributes["hx-delete"] = "/playlists/${playlist.id}"
                        attributes["hx-confirm"] = strings.deletePlaylistConfirm(playlist.title)
                        attributes["hx-target"] = "#playlist-notice"
                        +strings.deletePlaylist
                    }
                }
            }
            if (data.canModify) {
                details("add") {
                    summary("action") { +strings.editPlaylist }
                    form(classes = "fields") {
                        attributes["hx-patch"] = "/playlists/${playlist.id}"
                        attributes["hx-target"] = "#playlist-notice"
                        titleFields(playlist, strings)
                        playlist.rules?.let { rulesFields(it, strings) }
                        button(type = ButtonType.submit) { +strings.save }
                    }
                }
            }
            if (data.owner) {
                if (data.shares.isNotEmpty()) sharedWith(data.shares, strings, "#playlist-notice")
                if (data.others.isNotEmpty()) shareForm("/playlists/${playlist.id}/shares", data.others, strings, "#playlist-notice")
                feedSection(playlist.id, data.feedUrl, strings)
            }
            if (data.episodes.isEmpty()) p("empty") { +strings.emptyPlaylist }
            ul("episodes") {
                data.episodes.forEachIndexed { index, item -> playlistEpisodeRow(playlist.id, item, index, editable, strings, audio) }
            }
        }
    })
}

private fun UL.playlistRow(playlist: Playlist, strings: Strings, message: String? = null) {
    li("row") {
        a(href = "/playlists/${playlist.id}", classes = "card-body") {
            playlist.ownerName?.let { span("overline") { +strings.playlistSharedBy(it) } }
            span("title") { +playlist.title }
            span("meta") { +"${strings.playlistKind(playlist.kind)} · ${strings.episodeCount(playlist.episodeCount)}" }
            message?.let { q("message") { +it } }
        }
    }
}

private fun FORM.titleFields(playlist: Playlist?, strings: Strings) {
    label {
        +strings.playlistTitle
        input(type = InputType.text, name = "title") {
            required = true
            maxLength = PlaylistLimits.MAX_TITLE_LENGTH.toString()
            playlist?.let { value = it.title }
        }
    }
    label {
        +strings.description
        textArea(rows = "2") {
            name = "description"
            maxLength = PlaylistLimits.MAX_DESCRIPTION_LENGTH.toString()
            +(playlist?.description ?: "")
        }
    }
}

/** The rules of a dynamic playlist, the fields of `create-playlist` and `update-playlist`. */
private fun FlowContent.rulesFields(rules: PlaylistRules, strings: Strings) {
    label {
        +strings.keywords
        input(type = InputType.text, name = "keywords") {
            value = rules.keywords.joinToString(", ")
            placeholder = "kotlin, ktor"
        }
        span("hint") { +strings.keywordsHint }
    }
    booleanField("favoritesOnly", rules.favoritesOnly, strings.favoritesOnly)
    booleanField("unplayedOnly", rules.unplayedOnly, strings.unplayedOnly)
    label {
        +strings.minRating
        select {
            name = "minRating"
            (0..5).forEach { stars ->
                option {
                    value = stars.toString()
                    selected = stars == rules.minRating
                    +(if (stars == 0) strings.anyRating else strings.stars(stars))
                }
            }
        }
    }
    label {
        +strings.sortOrder
        select {
            name = "order"
            PlaylistOrder.entries.forEach { order ->
                option {
                    value = order.name
                    selected = order == rules.order
                    +strings.playlistOrder(order)
                }
            }
        }
    }
    label {
        +strings.episodeLimit
        input(type = InputType.number, name = "limit") {
            min = "1"
            max = PlaylistRules.MAX_LIMIT.toString()
            value = rules.limit.toString()
        }
    }
}

/** A checkbox always sent, true or false: the hidden "false" comes first, and the last value wins (receiveFields). */
private fun FlowContent.booleanField(name: String, checked: Boolean, label: String) {
    label("toggle") {
        input(type = InputType.hidden, name = name) { value = "false" }
        input(type = InputType.checkBox, name = name) {
            value = "true"
            this.checked = checked
        }
        +label
    }
}

/** The RSS feed, for the owner: its secret address to give to any podcast app, a new one, or none. */
private fun FlowContent.feedSection(playlistId: String, feedUrl: String?, strings: Strings) {
    section("feed") {
        h2 { +strings.feed }
        p("meta") { +strings.feedHint }
        feedUrl?.let { url ->
            input(type = InputType.text, classes = "feed-url") {
                readonly = true
                value = url
                attributes["aria-label"] = strings.feed
            }
        }
        div("actions") {
            button(classes = "action") {
                attributes["hx-put"] = "/playlists/$playlistId/feed"
                attributes["hx-swap"] = "none"
                +(if (feedUrl == null) strings.publishFeed else strings.newFeedAddress)
            }
            if (feedUrl != null) {
                button(classes = "action") {
                    attributes["hx-delete"] = "/playlists/$playlistId/feed"
                    attributes["hx-swap"] = "none"
                    +strings.unpublishFeed
                }
            }
        }
    }
}

/** An episode of the playlist; in a static playlist the reader may modify, `move-in-playlist` up and `remove-from-playlist`. */
private fun UL.playlistEpisodeRow(playlistId: String, item: EpisodeItem, index: Int, editable: Boolean, strings: Strings, audio: AudioLinks) {
    val episode = item.episode
    li("episode") {
        artwork(item)
        div("card-body") {
            span("overline") { +"${item.podcast.title} · ${strings.shortDate(episode.publishedOn())}" }
            span("title") { +episode.title }
            span("meta") { +strings.status(episode) }
            if (editable) {
                div("actions") {
                    if (index > 0) {
                        button(classes = "action") {
                            attributes["hx-put"] = "/playlists/$playlistId/episodes/${episode.id}"
                            attributes["hx-vals"] = buildJsonObject { put("position", index - 1) }.toString()
                            attributes["hx-swap"] = "none"
                            +strings.moveUp
                        }
                    }
                    button(classes = "action") {
                        attributes["hx-delete"] = "/playlists/$playlistId/episodes/${episode.id}"
                        attributes["hx-swap"] = "none"
                        +strings.removeFromPlaylist
                    }
                }
            }
        }
        playButton(item, strings, audio)
    }
}
