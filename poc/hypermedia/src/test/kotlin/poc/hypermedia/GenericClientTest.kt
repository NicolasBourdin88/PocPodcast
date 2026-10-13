package poc.hypermedia

import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A generic client only knows the URL of a resource and the *name* of the action it wants.
 * Method, target URL, encoding and default values must come from the representation.
 */
interface GenericClient {
    val format: Format

    /** Actions advertised by the representation. */
    fun actions(doc: JsonObject): Set<String>

    /** Builds the request for [action], or null if the format cannot describe it. */
    fun request(doc: JsonObject, action: String, values: Map<String, JsonElement>): ActionRequest?
}

data class ActionRequest(val method: HttpMethod, val url: String, val body: JsonObject?)

object HalFormsClient : GenericClient {
    override val format = Format.HalForms

    override fun actions(doc: JsonObject) = doc["_templates"]?.jsonObject?.keys.orEmpty()

    override fun request(doc: JsonObject, action: String, values: Map<String, JsonElement>): ActionRequest? {
        val template = doc["_templates"]?.jsonObject?.get(action)?.jsonObject ?: return null
        val target = template["target"]?.jsonPrimitive?.contentOrNull
            ?: doc["_links"]!!.jsonObject["self"]!!.jsonObject["href"]!!.jsonPrimitive.content
        val properties = template["properties"]?.jsonArray?.map { it.jsonObject }.orEmpty()
        val body = properties.associate { property ->
            val name = property["name"]!!.jsonPrimitive.content
            val value = values[name] ?: property["value"]
            val required = property["required"]?.jsonPrimitive?.boolean == true
            check(!required || value != null) { "Missing required property $name" }
            name to (value ?: JsonPrimitive(null as String?))
        }
        return ActionRequest(HttpMethod.parse(template["method"]!!.jsonPrimitive.content), target, JsonObject(body).takeIf { properties.isNotEmpty() })
    }

    /** HAL-FORMS can also list the allowed values of a property. */
    fun options(doc: JsonObject, action: String, property: String): List<String> =
        doc["_templates"]!!.jsonObject[action]!!.jsonObject["properties"]!!.jsonArray
            .map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == property }["options"]
            ?.jsonObject?.get("inline")?.jsonArray?.map { it.jsonObject["value"]!!.jsonPrimitive.content }.orEmpty()
}

object SirenClient : GenericClient {
    override val format = Format.Siren

    override fun actions(doc: JsonObject) =
        doc["actions"]?.jsonArray?.map { it.jsonObject["name"]!!.jsonPrimitive.content }?.toSet().orEmpty()

    override fun request(doc: JsonObject, action: String, values: Map<String, JsonElement>): ActionRequest? {
        val siren = doc["actions"]?.jsonArray?.map { it.jsonObject }
            ?.firstOrNull { it["name"]!!.jsonPrimitive.content == action } ?: return null
        val fields = siren["fields"]?.jsonArray?.map { it.jsonObject }.orEmpty()
        val body = fields.associate { field ->
            val name = field["name"]!!.jsonPrimitive.content
            name to (values[name] ?: field["value"] ?: JsonPrimitive(null as String?))
        }
        val method = siren["method"]?.jsonPrimitive?.content ?: "GET"
        return ActionRequest(HttpMethod.parse(method), siren["href"]!!.jsonPrimitive.content, JsonObject(body).takeIf { fields.isNotEmpty() })
    }
}

/** JSON:API has no actions: everything below is knowledge hard-coded in the client. */
object JsonApiClient : GenericClient {
    override val format = Format.JsonApi

    override fun actions(doc: JsonObject) = emptySet<String>()

    override fun request(doc: JsonObject, action: String, values: Map<String, JsonElement>): ActionRequest? = null
}

class GenericClientTest {

    private val report = StringBuilder()

    private suspend fun HttpClient.fetch(format: Format, url: String = "/episodes/e1"): JsonObject {
        val response = get(url) { accept(format.contentType) }
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    private suspend fun HttpClient.send(request: ActionRequest): HttpResponse = request(request.url) {
        method = request.method
        if (request.body != null) {
            contentType(ContentType.Application.Json)
            setBody(request.body.toString())
        }
    }

    @Test
    fun genericClientsPerformActionsKnowingOnlyTheirName() {
        for (client in listOf(HalFormsClient, SirenClient, JsonApiClient)) testApplication {
            val store = Store()
            application { pocModule(store) }
            val http = createClient { }

            val doc = http.fetch(client.format)
            val update = client.request(doc, "update-position", mapOf("position" to JsonPrimitive(754)))
            val markPlayed = client.request(doc, "mark-played", emptyMap())

            if (client == JsonApiClient) {
                assertEquals(null, update)
                assertEquals(null, markPlayed)
                report.appendLine("| ${client.format} | ${doc.toString().length} | non | non | non | non |")
                return@testApplication
            }

            assertTrue(http.send(update!!).status.isSuccess())
            assertEquals(754, store.episodes["e1"]!!.positionSeconds)

            assertTrue(http.send(markPlayed!!).status.isSuccess())
            // Once played, the server stops offering the transition.
            assertFalse("mark-played" in client.actions(http.fetch(client.format)))

            val choices = if (client == HalFormsClient) HalFormsClient.options(doc, "add-to-playlist", "playlistId") else emptyList()
            report.appendLine(
                "| ${client.format} | ${doc.toString().length} | oui | oui | " +
                    "${if (choices.isNotEmpty()) "oui (${choices.size})" else "non"} | " +
                    "${if (client == HalFormsClient) "oui" else "non"} |",
            )
        }
    }

    @Test
    fun jsonApiNeedsOutOfBandKnowledge() = testApplication {
        val store = Store()
        application { pocModule(store) }
        val http = createClient { }

        // The client must know that "update position" is a PATCH on self with this exact body shape.
        val self = http.fetch(Format.JsonApi)["data"]!!.jsonObject["links"]!!.jsonObject["self"]!!.jsonPrimitive.content
        val body = Json.parseToJsonElement(
            """{"data":{"type":"episodes","id":"e1","attributes":{"positionSeconds":754}}}""",
        ).jsonObject
        assertTrue(http.send(ActionRequest(HttpMethod.Patch, self, body)).status.isSuccess())
        assertEquals(754, store.episodes["e1"]!!.positionSeconds)
    }

    @AfterTest
    fun writeReport() {
        if (report.isEmpty()) return
        val text = buildString {
            appendLine("| Format | Taille (octets) | Actions découvrables | Actions conditionnelles | Choix (options) | Validation (required/min) |")
            appendLine("|---|---|---|---|---|---|")
            append(report)
        }
        println(text)
        System.getProperty("poc.report")?.let { File(it).apply { parentFile.mkdirs() }.writeText(text) }
    }
}
