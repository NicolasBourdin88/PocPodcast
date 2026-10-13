package fr.podcast.shared.api

import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** What the SDK asks for: HAL with the available actions (ADR 0003). */
val HalFormsJson = ContentType("application", "prs.hal-forms+json")

/** Lenient on purpose: clients ignore what they do not know (docs/api/conventions.md). */
internal val apiJson = Json { ignoreUnknownKeys = true }

/** A HAL document with its HAL-FORMS templates. */
class HalResource(val json: JsonObject) {

    fun link(rel: String): String? = json.objectAt("_links")?.objectAt(rel)?.stringAt("href")

    fun embedded(rel: String): List<HalResource> = when (val value = json.objectAt("_embedded")?.get(rel)) {
        is JsonArray -> value.map { HalResource(it.jsonObject) }
        is JsonObject -> listOf(HalResource(value))
        else -> emptyList()
    }

    fun template(name: String): HalFormsTemplate? {
        val template = json.objectAt("_templates")?.objectAt(name) ?: return null
        return HalFormsTemplate(
            name = name,
            method = template.stringAt("method")?.let(HttpMethod::parse) ?: HttpMethod.Get,
            target = template.stringAt("target"),
            properties = (template["properties"] as? JsonArray).orEmpty().map { property ->
                val obj = property.jsonObject
                HalFormsProperty(
                    name = obj.stringAt("name").orEmpty(),
                    required = (obj["required"] as? JsonPrimitive)?.booleanOrNull == true,
                    value = obj["value"],
                )
            },
        )
    }

    val templateNames: Set<String> get() = json.objectAt("_templates")?.keys.orEmpty()

    fun string(name: String): String? = json.stringAt(name)

    fun int(name: String): Int? = (json[name] as? JsonPrimitive)?.intOrNull

    /** Reads the resource fields into a :shared model (links and templates are ignored). */
    fun <T> decode(serializer: KSerializer<T>): T = apiJson.decodeFromJsonElement(serializer, json)
}

data class HalFormsTemplate(
    val name: String,
    val method: HttpMethod,
    /** Null means "the resource itself" (HAL-FORMS default). */
    val target: String?,
    val properties: List<HalFormsProperty>,
)

data class HalFormsProperty(val name: String, val required: Boolean, val value: JsonElement?)

/**
 * Minimal RFC 6570 expansion, enough for the links of this API: `{id}` and `{?status,offset,limit}`.
 * Variables without a value are left out.
 */
fun expandUriTemplate(template: String, values: Map<String, Any?>): String =
    // The closing brace must be escaped: Android's regex engine (ICU) rejects a bare "}".
    Regex("""\{(\??)([^}]+)\}""").replace(template) { match ->
        val (operator, names) = match.destructured
        val variables = names.split(',').map { it.trim() }
        if (operator == "?") {
            val pairs = variables.mapNotNull { name -> values[name]?.let { "$name=${it.toString().encodeURLParameter()}" } }
            if (pairs.isEmpty()) "" else "?" + pairs.joinToString("&")
        } else {
            variables.joinToString(",") { name -> values[name]?.toString()?.encodeURLParameter().orEmpty() }
        }
    }

private fun JsonObject.objectAt(name: String): JsonObject? = get(name) as? JsonObject

private fun JsonObject.stringAt(name: String): String? = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
