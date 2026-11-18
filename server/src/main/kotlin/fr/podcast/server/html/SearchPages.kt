package fr.podcast.server.html

import fr.podcast.server.auth.UserAccount
import fr.podcast.server.catalog.Page
import fr.podcast.server.media.AudioLinks
import fr.podcast.shared.home.EpisodeItem
import fr.podcast.shared.i18n.Language
import fr.podcast.shared.i18n.Strings
import fr.podcast.shared.model.EpisodeQuery
import fr.podcast.shared.model.EpisodeStatus
import fr.podcast.shared.model.SubscriptionItem
import fr.podcast.shared.navigation.Destination
import io.ktor.http.encodeURLParameter
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.InputType
import kotlinx.html.SELECT
import kotlinx.html.UL
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.fieldSet
import kotlinx.html.form
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.li
import kotlinx.html.nav
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.select
import kotlinx.html.span
import kotlinx.html.ul

/** A search of the user's episodes (ADR 0012): the filters, those given, a page of results, what to choose from. */
data class EpisodeSearch(
    val query: EpisodeQuery,
    val given: Map<String, String>,
    val page: Page<EpisodeItem>,
    val podcasts: List<SubscriptionItem>,
    val tags: List<String>,
)

/**
 * The episode search (GET /episodes in HTML, ADR 0012): every criterion of the API in one form,
 * sent by GET so that a search has its address; boosted, so that the player keeps going.
 */
fun HTML.episodeSearchPage(search: EpisodeSearch, language: Language, user: UserAccount, audio: AudioLinks) {
    val strings = language.strings
    val query = search.query
    page(language, strings.searchEpisodes, user, Destination.Library, content = {
        form(action = "/episodes", method = FormMethod.get, classes = "fields search-episodes") {
            attributes["hx-boost"] = "true"
            label {
                +strings.keywords
                input(type = InputType.search, name = "q") {
                    value = query.text
                    placeholder = "kotlin ktor"
                }
                span("hint") { +strings.searchEpisodesHint }
            }
            fieldSet("choices") {
                labeledSelect(strings.statusLabel, "status") {
                    choice("", strings.anyStatus, query.status == null)
                    EpisodeStatus.entries.forEach { choice(it.name, strings.statusFilter(it), query.status == it) }
                }
                labeledSelect(strings.minRating, "minRating") {
                    (0..5).forEach { stars -> choice(stars.toString(), if (stars == 0) strings.anyRating else strings.stars(stars), query.minRating == stars) }
                }
                labeledSelect(strings.podcastLabel, "podcast") {
                    choice("", strings.anyPodcast, query.podcastId == null)
                    search.podcasts.forEach { item -> choice(item.podcast.id, item.podcast.title, query.podcastId == item.podcast.id) }
                }
                if (search.tags.isNotEmpty()) {
                    labeledSelect(strings.tags, "tag") {
                        choice("", strings.allTags, query.tag == null)
                        search.tags.forEach { tag -> choice(tag, tag, tag.equals(query.tag, ignoreCase = true)) }
                    }
                }
            }
            fieldSet("choices") {
                label("toggle") {
                    input(type = InputType.hidden, name = "favorite") { value = "false" }
                    input(type = InputType.checkBox, name = "favorite") {
                        value = "true"
                        checked = query.favoritesOnly
                    }
                    +strings.favoritesOnly
                }
                label {
                    +"${strings.period} · ${strings.publishedFrom}"
                    input(type = InputType.date, name = "from") { query.from?.let { value = it.toString() } }
                }
                label {
                    +strings.publishedTo
                    input(type = InputType.date, name = "to") { query.to?.let { value = it.toString() } }
                }
            }
            button(type = ButtonType.submit) { +strings.search }
        }

        val results = search.page
        p("meta") { +strings.episodeCount(results.total.toInt()) }
        if (results.items.isEmpty()) p("empty") { +strings.noEpisodeFound }
        ul("episodes") { results.items.forEach { searchResult(it, strings, audio) } }
        pages(search, strings)
    })
}

private fun FlowContent.labeledSelect(label: String, name: String, options: SELECT.() -> Unit) {
    label {
        +label
        select {
            this.name = name
            options()
        }
    }
}

private fun SELECT.choice(value: String, text: String, selected: Boolean) {
    option {
        this.value = value
        this.selected = selected
        +text
    }
}

private fun UL.searchResult(item: EpisodeItem, strings: Strings, audio: AudioLinks) {
    val episode = item.episode
    li("episode") {
        artwork(item)
        div("card-body") {
            span("overline") { +"${item.podcast.title} · ${strings.shortDate(episode.publishedOn())}" }
            a(href = "/episodes/${episode.id}", classes = "title") { +episode.title }
            span("meta") {
                +(listOfNotNull(strings.duration(episode.durationSeconds).takeIf { episode.durationSeconds > 0 }, strings.status(episode)) + strings.marks(episode))
                    .joinToString(" · ")
            }
        }
        playButton(item, strings, audio)
    }
}

/** Previous and next pages, with the same filters. */
private fun FlowContent.pages(search: EpisodeSearch, strings: Strings) {
    val page = search.page
    fun href(offset: Int) = "/episodes?" + (search.given + mapOf("offset" to "$offset", "limit" to "${page.limit}"))
        .entries.joinToString("&") { (key, value) -> "$key=${value.encodeURLParameter()}" }
    val previous = (page.offset - page.limit).takeIf { page.offset > 0 }?.coerceAtLeast(0)
    val next = (page.offset + page.limit).takeIf { it < page.total }
    if (previous == null && next == null) return
    nav("pages") {
        attributes["hx-boost"] = "true"
        previous?.let { a(href = href(it), classes = "button") { +strings.previousPage } }
        next?.let { a(href = href(it), classes = "button") { +strings.nextPage } }
    }
}
