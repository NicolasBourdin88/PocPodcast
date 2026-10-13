package fr.podcast.server.hal

import fr.podcast.shared.model.Episode
import fr.podcast.shared.model.EpisodeStatus
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * An action available on a resource in its current state. The same object is rendered as a
 * HAL-FORMS template for the SDK and as an htmx control for the thin client (ADR 0003).
 */
data class Affordance(
    val name: String,
    val method: HttpMethod,
    val target: String,
    val properties: List<AffordanceProperty> = emptyList(),
)

data class AffordanceProperty(
    val name: String,
    val type: String? = null,
    val required: Boolean = false,
    val value: JsonPrimitive? = null,
    val min: Int? = null,
    val max: Int? = null,
)

object Affordances {
    val createToken = Affordance(
        "create-token", HttpMethod.Post, "/auth/tokens",
        listOf(
            AffordanceProperty("username", required = true),
            AffordanceProperty("password", type = "password", required = true),
            AffordanceProperty("deviceName", required = true),
        ),
    )

    val logout = Affordance("logout", HttpMethod.Delete, "/auth/tokens/current")

    /** `update-progress` always; `mark-played` unless played; `mark-unplayed` unless new. */
    fun forEpisode(episode: Episode): List<Affordance> = buildList {
        add(
            Affordance(
                "update-progress", HttpMethod.Put, "/episodes/${episode.id}/progress",
                listOf(
                    AffordanceProperty(
                        "positionSeconds", type = "number", required = true,
                        value = JsonPrimitive(episode.positionSeconds), min = 0, max = episode.durationSeconds,
                    ),
                ),
            ),
        )
        if (episode.status != EpisodeStatus.PLAYED) add(Affordance("mark-played", HttpMethod.Post, "/episodes/${episode.id}/played"))
        if (episode.status != EpisodeStatus.NEW) add(Affordance("mark-unplayed", HttpMethod.Delete, "/episodes/${episode.id}/played"))
    }
}

/** `_templates` object of a HAL-FORMS document. */
fun List<Affordance>.toHalFormsTemplates(): JsonObject = buildJsonObject {
    for (affordance in this@toHalFormsTemplates) {
        putJsonObject(affordance.name) {
            put("method", affordance.method.value)
            put("target", affordance.target)
            put("contentType", "application/json")
            if (affordance.properties.isNotEmpty()) {
                putJsonArray("properties") {
                    for (property in affordance.properties) {
                        addJsonObject {
                            put("name", property.name)
                            property.type?.let { put("type", it) }
                            if (property.required) put("required", true)
                            property.value?.let { put("value", it) }
                            property.min?.let { put("min", it) }
                            property.max?.let { put("max", it) }
                        }
                    }
                }
            }
        }
    }
}
