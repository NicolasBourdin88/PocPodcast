package fr.podcast.server.hal

import fr.podcast.shared.home.EpisodeItem
import fr.podcast.shared.home.HomeState
import fr.podcast.shared.model.Episode
import fr.podcast.shared.model.Podcast
import io.ktor.http.ContentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * HAL representations (https://datatracker.ietf.org/doc/html/draft-kelly-json-hal).
 * The format is provisional until the hypermedia study (step 2) is decided.
 */
val HalJson = ContentType("application", "hal+json")

private fun JsonObjectBuilder.links(vararg links: Pair<String, String>) {
    putJsonObject("_links") {
        links.forEach { (rel, href) -> putJsonObject(rel) { put("href", href) } }
    }
}

/** Copies the model's fields, so HAL stays in sync with the :shared models. */
private inline fun <reified T> JsonObjectBuilder.fields(value: T) {
    Json.encodeToJsonElement(value).jsonObject.forEach { (key, element) -> put(key, element) }
}

fun podcastResource(podcast: Podcast): JsonObject = buildJsonObject {
    links(
        "self" to "/podcasts/${podcast.id}",
        "episodes" to "/podcasts/${podcast.id}/episodes",
    )
    fields(podcast)
}

fun episodeResource(episode: Episode, podcast: Podcast?): JsonObject = buildJsonObject {
    links(
        "self" to "/episodes/${episode.id}",
        "podcast" to "/podcasts/${episode.podcastId}",
        "play" to "/episodes/${episode.id}/audio",
    )
    fields(episode)
    put("progress", episode.progress)
    put("remainingMinutes", episode.remainingMinutes)
    put("durationLabel", episode.durationLabel)
    if (podcast != null) {
        putJsonObject("_embedded") { put("podcast", podcastResource(podcast)) }
    }
}

private fun episodeArray(items: List<EpisodeItem>) = buildJsonArray {
    items.forEach { add(episodeResource(it.episode, it.podcast)) }
}

fun homeResource(state: HomeState): JsonObject = buildJsonObject {
    links(
        "self" to "/",
        "podcasts" to "/podcasts",
        "episodes" to "/episodes",
    )
    putJsonObject("_embedded") {
        put("resume", episodeArray(state.resume))
        put("latest", episodeArray(state.latest))
    }
}

fun podcastCollection(podcasts: List<Podcast>): JsonObject = buildJsonObject {
    links("self" to "/podcasts")
    put("count", podcasts.size)
    putJsonObject("_embedded") {
        put("podcasts", buildJsonArray { podcasts.forEach { add(podcastResource(it)) } })
    }
}

fun episodeCollection(
    episodes: List<Episode>,
    podcasts: Map<String, Podcast>,
    self: String = "/episodes",
): JsonObject = buildJsonObject {
    links("self" to self)
    put("count", episodes.size)
    putJsonObject("_embedded") {
        put("episodes", buildJsonArray { episodes.forEach { add(episodeResource(it, podcasts[it.podcastId])) } })
    }
}
