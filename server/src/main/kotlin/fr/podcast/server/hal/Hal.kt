package fr.podcast.server.hal

import fr.podcast.server.auth.IssuedToken
import fr.podcast.server.auth.UserAccount
import fr.podcast.server.catalog.Page
import fr.podcast.server.search.SearchHit
import fr.podcast.server.search.normalizedFeed
import io.ktor.http.encodeURLParameter
import fr.podcast.shared.home.EpisodeItem
import fr.podcast.shared.home.HomeState
import fr.podcast.shared.model.Episode
import fr.podcast.shared.model.OpmlImportReport
import fr.podcast.shared.model.Podcast
import fr.podcast.shared.model.SearchResult
import fr.podcast.shared.model.Subscription
import fr.podcast.shared.model.SubscriptionItem
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/*
 * HAL (+ HAL-FORMS when `forms` is true) representations, see docs/api/conventions.md
 * and the OpenAPI schemas. Model fields come from the :shared serializers, so the SDK
 * reads exactly what the server writes.
 */

const val API_VERSION = 1

// Every field is written, including defaults and nulls: the contract lists them as required.
private val modelJson = Json {
    encodeDefaults = true
    explicitNulls = true
}

/** A HAL link; [type] and [hreflang] describe external resources (transcripts…). */
data class Link(val href: String, val type: String? = null, val hreflang: String? = null)

private fun <T> JsonObjectBuilder.fields(serializer: KSerializer<T>, value: T) {
    modelJson.encodeToJsonElement(serializer, value).jsonObject.forEach { (key, element) -> put(key, element) }
}

private fun JsonObjectBuilder.link(link: Link) {
    put("href", link.href)
    if ('{' in link.href) put("templated", true)
    link.type?.let { put("type", it) }
    link.hreflang?.let { put("hreflang", it) }
}

/** `_links`: one link per relation, or an array when a relation has several (transcripts). */
private fun JsonObjectBuilder.links(
    single: List<Pair<String, String>>,
    extra: List<Pair<String, Link>> = emptyList(),
    multiple: Map<String, List<Link>> = emptyMap(),
) {
    putJsonObject("_links") {
        for ((rel, href) in single) putJsonObject(rel) { link(Link(href)) }
        for ((rel, link) in extra) putJsonObject(rel) { link(link) }
        for ((rel, links) in multiple) {
            if (links.isNotEmpty()) putJsonArray(rel) { links.forEach { addJsonObject { link(it) } } }
        }
    }
}

private fun JsonObjectBuilder.links(vararg single: Pair<String, String>) = links(single.toList())

private fun JsonObjectBuilder.templates(forms: Boolean, affordances: List<Affordance>) {
    if (forms && affordances.isNotEmpty()) put("_templates", affordances.toHalFormsTemplates())
}

fun rootResource(serverName: String, user: UserAccount?, home: HomeState?, forms: Boolean): JsonObject = buildJsonObject {
    put("name", serverName)
    put("apiVersion", API_VERSION)
    if (user == null) {
        links("self" to "/", "tokens" to "/auth/tokens")
        templates(forms, listOf(Affordances.createToken))
    } else {
        links(
            "self" to "/",
            "tokens" to "/auth/tokens",
            "me" to "/me",
            "subscriptions" to "/subscriptions",
            "episodes" to "/episodes{?status,offset,limit}",
            "episode" to "/episodes/{id}",
            "podcast" to "/podcasts/{id}",
            "search" to "/search{?q}",
        )
        if (home != null) {
            putJsonObject("_embedded") {
                put("resume", buildJsonArray { home.resume.forEach { add(episodeResource(it, forms)) } })
                put("latest", buildJsonArray { home.latest.forEach { add(episodeResource(it, forms)) } })
            }
        }
    }
}

fun tokenResource(token: IssuedToken): JsonObject = buildJsonObject {
    put("token", token.value)
    put("deviceName", token.deviceName)
    put("createdAt", token.createdAt.toString())
    links("self" to "/auth/tokens/current", "me" to "/me")
}

fun userResource(user: UserAccount, forms: Boolean): JsonObject = buildJsonObject {
    put("id", user.id.toString())
    put("username", user.username)
    put("displayName", user.displayName)
    put("role", user.role.name)
    links("self" to "/me")
    templates(forms, listOf(Affordances.logout))
}

fun podcastResource(podcast: Podcast, forms: Boolean = false): JsonObject = buildJsonObject {
    fields(Podcast.serializer(), podcast)
    links(
        "self" to "/podcasts/${podcast.id}",
        "episodes" to "/podcasts/${podcast.id}/episodes",
        "feed" to podcast.feedUrl,
    )
    templates(forms, Affordances.forPodcast(podcast))
}

fun subscriptionResource(item: SubscriptionItem, forms: Boolean): JsonObject = buildJsonObject {
    fields(Subscription.serializer(), item.subscription)
    links("self" to "/subscriptions/${item.subscription.id}", "podcast" to "/podcasts/${item.podcast.id}")
    putJsonObject("_embedded") { put("podcast", podcastResource(item.podcast)) }
    templates(forms, Affordances.forSubscription(item))
}

fun subscriptionCollection(items: List<SubscriptionItem>, forms: Boolean): JsonObject = buildJsonObject {
    put("count", items.size)
    links("self" to "/subscriptions", "opml" to "/subscriptions/opml")
    putJsonObject("_embedded") {
        put("subscriptions", buildJsonArray { items.forEach { add(subscriptionResource(it, forms)) } })
    }
    templates(forms, listOf(Affordances.subscribe))
}

fun episodeResource(item: EpisodeItem, forms: Boolean): JsonObject = buildJsonObject {
    val episode: Episode = item.episode
    fields(Episode.serializer(), episode)
    links(
        single = listOf(
            "self" to "/episodes/${episode.id}",
            "podcast" to "/podcasts/${episode.podcastId}",
            "audio" to "/episodes/${episode.id}/audio",
        ),
        // Podcast 2.0: external resources announced by the feed.
        extra = listOfNotNull(episode.chaptersUrl?.let { "chapters" to Link(it, type = "application/json+chapters") }),
        multiple = mapOf("transcript" to episode.transcripts.map { Link(it.url, it.type, it.language) }),
    )
    putJsonObject("_embedded") { put("podcast", podcastResource(item.podcast)) }
    templates(forms, Affordances.forEpisode(episode))
}

/** A page of episodes; [path] plus [query] (without paging) rebuild the page links. */
fun episodeCollection(page: Page<EpisodeItem>, path: String, query: Map<String, String>, forms: Boolean): JsonObject = buildJsonObject {
    fun pageHref(offset: Int) = path + "?" + (query + mapOf("offset" to "$offset", "limit" to "${page.limit}"))
        .entries.joinToString("&") { (key, value) -> "$key=$value" }

    put("count", page.items.size)
    put("total", page.total)
    put("offset", page.offset)
    put("limit", page.limit)
    links(
        buildList {
            add("self" to pageHref(page.offset))
            if (page.offset + page.limit < page.total) add("next" to pageHref(page.offset + page.limit))
            if (page.offset > 0) add("prev" to pageHref(maxOf(0, page.offset - page.limit)))
        },
    )
    putJsonObject("_embedded") {
        put("episodes", buildJsonArray { page.items.forEach { add(episodeResource(it, forms)) } })
    }
}

fun opmlImportResource(report: OpmlImportReport): JsonObject = buildJsonObject {
    fields(OpmlImportReport.serializer(), report)
    links("subscriptions" to "/subscriptions")
}

/** Search results; each one can be subscribed to with its pre-filled `subscribe` template. */
fun searchResults(query: String, hits: List<SearchHit>, answeredBy: List<String>, subscribed: Set<String>, forms: Boolean): JsonObject = buildJsonObject {
    put("query", query)
    put("count", hits.size)
    putJsonArray("sources") { answeredBy.forEach { add(JsonPrimitive(it)) } }
    links("self" to "/search?q=${query.encodeURLParameter()}")
    putJsonObject("_embedded") {
        put(
            "results",
            buildJsonArray {
                for (hit in hits) {
                    val isSubscribed = normalizedFeed(hit.feedUrl) in subscribed
                    add(
                        buildJsonObject {
                            fields(SearchResult.serializer(), SearchResult(hit.title, hit.author, hit.feedUrl, hit.artworkUrl, hit.description, hit.source, isSubscribed))
                            links("feed" to hit.feedUrl)
                            templates(forms, if (isSubscribed) emptyList() else listOf(Affordances.subscribeTo(hit.feedUrl)))
                        },
                    )
                }
            },
        )
    }
}
