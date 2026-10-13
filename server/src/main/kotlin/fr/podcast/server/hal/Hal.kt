package fr.podcast.server.hal

import fr.podcast.server.auth.IssuedToken
import fr.podcast.server.auth.UserAccount
import fr.podcast.server.catalog.Page
import fr.podcast.server.catalog.Subscription
import fr.podcast.shared.home.EpisodeItem
import fr.podcast.shared.home.HomeState
import fr.podcast.shared.model.Episode
import fr.podcast.shared.model.Podcast
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
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

private fun <T> JsonObjectBuilder.fields(serializer: KSerializer<T>, value: T) {
    modelJson.encodeToJsonElement(serializer, value).jsonObject.forEach { (key, element) -> put(key, element) }
}

private fun JsonObjectBuilder.links(vararg links: Pair<String, String>) {
    putJsonObject("_links") {
        for ((rel, href) in links) {
            putJsonObject(rel) {
                put("href", href)
                if ('{' in href) put("templated", true)
            }
        }
    }
}

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

fun podcastResource(podcast: Podcast): JsonObject = buildJsonObject {
    fields(Podcast.serializer(), podcast)
    links(
        "self" to "/podcasts/${podcast.id}",
        "episodes" to "/podcasts/${podcast.id}/episodes",
        "feed" to podcast.feedUrl,
    )
}

fun subscriptionResource(subscription: Subscription): JsonObject = buildJsonObject {
    put("id", subscription.id)
    put("note", subscription.note)
    put("subscribedAt", subscription.subscribedAt.toString())
    links("self" to "/subscriptions/${subscription.id}", "podcast" to "/podcasts/${subscription.podcast.id}")
    putJsonObject("_embedded") { put("podcast", podcastResource(subscription.podcast)) }
}

fun subscriptionCollection(subscriptions: List<Subscription>): JsonObject = buildJsonObject {
    put("count", subscriptions.size)
    links("self" to "/subscriptions")
    putJsonObject("_embedded") {
        put("subscriptions", buildJsonArray { subscriptions.forEach { add(subscriptionResource(it)) } })
    }
}

fun episodeResource(item: EpisodeItem, forms: Boolean): JsonObject = buildJsonObject {
    val episode: Episode = item.episode
    fields(Episode.serializer(), episode)
    links(
        "self" to "/episodes/${episode.id}",
        "podcast" to "/podcasts/${episode.podcastId}",
        "audio" to "/episodes/${episode.id}/audio",
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
    val links = buildList {
        add("self" to pageHref(page.offset))
        if (page.offset + page.limit < page.total) add("next" to pageHref(page.offset + page.limit))
        if (page.offset > 0) add("prev" to pageHref(maxOf(0, page.offset - page.limit)))
    }
    links(*links.toTypedArray())
    putJsonObject("_embedded") {
        put("episodes", buildJsonArray { page.items.forEach { add(episodeResource(it, forms)) } })
    }
}
