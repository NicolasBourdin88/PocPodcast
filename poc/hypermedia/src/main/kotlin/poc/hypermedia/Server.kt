package poc.hypermedia

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.acceptItems
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class Episode(val id: String, val title: String, var positionSeconds: Int = 0, var played: Boolean = false)
data class Playlist(val id: String, val title: String, val episodeIds: MutableList<String> = mutableListOf())

class Store {
    val episodes = mutableMapOf("e1" to Episode("e1", "KMP in production"))
    val playlists = mutableMapOf(
        "pl1" to Playlist("pl1", "Trajet du matin"),
        "pl2" to Playlist("pl2", "À écouter un jour"),
    )
}

/** The three candidate formats, each able to render an episode with its available actions. */
enum class Format(val contentType: ContentType) {
    HalForms(ContentType("application", "prs.hal-forms+json")),
    Siren(ContentType("application", "vnd.siren+json")),
    JsonApi(ContentType("application", "vnd.api+json")),
}

fun Application.pocModule(store: Store = Store()) {
    routing {
        get("/episodes/{id}") {
            val episode = store.episodes[call.parameters["id"]] ?: return@get call.respond(HttpStatusCode.NotFound)
            val format = call.requestedFormat() ?: return@get call.respond(HttpStatusCode.NotAcceptable)
            val body = when (format) {
                Format.HalForms -> halFormsEpisode(episode, store.playlists.values)
                Format.Siren -> sirenEpisode(episode, store.playlists.values)
                Format.JsonApi -> jsonApiEpisode(episode)
            }
            call.respondText(body.toString(), format.contentType)
        }

        // Endpoints targeted by HAL-FORMS templates and Siren actions.
        put("/episodes/{id}/position") {
            val episode = store.episodes[call.parameters["id"]] ?: return@put call.respond(HttpStatusCode.NotFound)
            val position = call.jsonBody()["position"]?.jsonPrimitive?.int
                ?: return@put call.respond(HttpStatusCode.UnprocessableEntity)
            episode.positionSeconds = position
            call.respond(HttpStatusCode.NoContent)
        }
        post("/episodes/{id}/played") {
            val episode = store.episodes[call.parameters["id"]] ?: return@post call.respond(HttpStatusCode.NotFound)
            if (episode.played) return@post call.respond(HttpStatusCode.Conflict)
            episode.played = true
            call.respond(HttpStatusCode.NoContent)
        }
        post("/playlist-items") {
            val body = call.jsonBody()
            val playlist = store.playlists[body["playlistId"]?.jsonPrimitive?.content]
            val episodeId = body["episodeId"]?.jsonPrimitive?.content
            if (playlist == null || episodeId !in store.episodes) return@post call.respond(HttpStatusCode.UnprocessableEntity)
            playlist.episodeIds += episodeId!!
            call.respond(HttpStatusCode.Created)
        }

        // JSON:API style: generic PATCH of attributes and relationship endpoints.
        patch("/episodes/{id}") {
            val episode = store.episodes[call.parameters["id"]] ?: return@patch call.respond(HttpStatusCode.NotFound)
            val attributes = call.jsonBody()["data"]?.jsonObject?.get("attributes")?.jsonObject
                ?: return@patch call.respond(HttpStatusCode.UnprocessableEntity)
            attributes["positionSeconds"]?.let { episode.positionSeconds = it.jsonPrimitive.int }
            attributes["played"]?.let { episode.played = it.jsonPrimitive.boolean }
            call.respond(HttpStatusCode.NoContent)
        }
        post("/playlists/{id}/relationships/episodes") {
            val playlist = store.playlists[call.parameters["id"]] ?: return@post call.respond(HttpStatusCode.NotFound)
            call.jsonBody()["data"]!!.jsonArray.forEach { playlist.episodeIds += it.jsonObject["id"]!!.jsonPrimitive.content }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun ApplicationCall.requestedFormat(): Format? = request.acceptItems()
    .map { ContentType.parse(it.value) }
    .firstNotNullOfOrNull { accepted -> Format.entries.firstOrNull { accepted.match(it.contentType) } }

private suspend fun ApplicationCall.jsonBody(): JsonObject = Json.parseToJsonElement(receiveText()).jsonObject
