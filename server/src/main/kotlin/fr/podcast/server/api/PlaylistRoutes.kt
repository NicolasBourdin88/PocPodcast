package fr.podcast.server.api

import fr.podcast.server.ServerServices
import fr.podcast.server.auth.Action
import fr.podcast.server.auth.BEARER_AUTH
import fr.podcast.server.auth.SESSION_AUTH
import fr.podcast.server.auth.UserAccount
import fr.podcast.server.auth.can
import fr.podcast.server.catalog.PlaylistView
import fr.podcast.server.hal.playlistCollection
import fr.podcast.server.hal.playlistResource
import fr.podcast.server.http.FieldError
import fr.podcast.server.http.isHtmx
import fr.podcast.server.http.representation
import fr.podcast.server.http.respondProblem
import fr.podcast.shared.model.Playlist
import fr.podcast.shared.model.PlaylistKind
import fr.podcast.shared.model.PlaylistLimits
import fr.podcast.shared.model.PlaylistOrder
import fr.podcast.shared.model.PlaylistRules
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.response.header
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Playlists (ADR 0010): the user's own, their content, and the changes their rights allow (`can`):
 * 404 when the user cannot see a playlist, 403 when they can see it but not do this.
 */
fun Route.playlistRoutes(services: ServerServices) {
    val playlists = services.playlists

    /** The playlist [user] may read; null after a 404. */
    suspend fun ApplicationCall.visiblePlaylist(user: UserAccount): PlaylistView? {
        val view = playlists.view(user, parameters["id"]!!)
        if (view == null) respondNotFound("Playlist")
        return view
    }

    /** The playlist [user] may do [action] on; null after a 404 or a 403. */
    suspend fun ApplicationCall.playlistFor(user: UserAccount, action: Action): PlaylistView? {
        val view = visiblePlaylist(user) ?: return null
        if (!user.can(action, view.ownerId, view.permission)) {
            val reason = if (action == Action.MODIFY) "This playlist is shared with you read-only" else "Only its owner can do that"
            respondProblem(HttpStatusCode.Forbidden, reason)
            return null
        }
        return view
    }

    /** A static playlist [user] may modify; null after a 404, a 403 or a 409 (a dynamic playlist). */
    suspend fun ApplicationCall.staticPlaylistFor(user: UserAccount): PlaylistView? {
        val view = playlistFor(user, Action.MODIFY) ?: return null
        if (view.playlist.kind == PlaylistKind.DYNAMIC) {
            respondProblem(HttpStatusCode.Conflict, "A dynamic playlist is computed from its rules: its episodes do not change one by one")
            return null
        }
        return view
    }

    /** The playlist with its episodes, as [user] sees it now. */
    suspend fun ApplicationCall.respondPlaylist(id: Long, user: UserAccount, status: HttpStatusCode = HttpStatusCode.OK) {
        val view = playlists.view(user, id.toString())!!
        val representation = representation()
        respondResource(playlistResource(view, user, representation.forms, services.audioLinks, playlists.episodes(view, user.id)), representation, status)
    }

    authenticate(SESSION_AUTH, BEARER_AUTH) {
        get("/playlists") {
            val user = call.user
            val representation = call.representation()
            call.respondResource(playlistCollection(playlists.playlistsOf(user), user, representation.forms, services.audioLinks), representation)
        }

        post("/playlists") {
            val user = call.mutatingUser() ?: return@post
            val request = call.receivePlaylistRequest(current = null) ?: return@post
            val id = playlists.create(user.id, request.title, request.description, request.kind, request.rules)
            call.response.header(HttpHeaders.Location, "/playlists/$id")
            call.respondPlaylist(id, user, HttpStatusCode.Created)
        }

        get("/playlists/{id}") {
            val user = call.user
            val view = call.visiblePlaylist(user) ?: return@get
            val representation = call.representation()
            call.respondResource(playlistResource(view, user, representation.forms, services.audioLinks, playlists.episodes(view, user.id)), representation)
        }

        patch("/playlists/{id}") {
            val user = call.mutatingUser() ?: return@patch
            val view = call.playlistFor(user, Action.MODIFY) ?: return@patch
            val request = call.receivePlaylistRequest(current = view.playlist) ?: return@patch
            playlists.update(view.id, request.title, request.description, request.rules)
            call.respondPlaylist(view.id, user)
        }

        delete("/playlists/{id}") {
            val user = call.mutatingUser() ?: return@delete
            val view = call.playlistFor(user, Action.DELETE) ?: return@delete
            playlists.delete(view.id)
            call.respondChanged("playlists-changed")
        }

        post("/playlists/{id}/episodes") {
            val user = call.mutatingUser() ?: return@post
            val view = call.staticPlaylistFor(user) ?: return@post
            val fields = call.receiveFields() ?: emptyMap()
            val episodeId = fields.stringField("episodeId") ?: fields.intField("episodeId")?.toString()
            val position = fields.intField("position")
            val errors = buildList {
                if (episodeId.isNullOrBlank()) add(FieldError("episodeId", "required"))
                if (fields.hasField("position") && (position == null || position < 0)) add(FieldError("position", "0 or more"))
            }
            if (errors.isNotEmpty()) return@post call.respondProblem(HttpStatusCode.UnprocessableEntity, "Invalid item", errors)
            if (!playlists.add(view.id, episodeId!!, position)) {
                return@post call.respondProblem(
                    HttpStatusCode.UnprocessableEntity, "Unknown episode", listOf(FieldError("episodeId", "an existing episode expected")),
                )
            }
            if (call.isHtmx) return@post call.respondChanged("playlists-changed")
            call.respondPlaylist(view.id, user)
        }

        put("/playlists/{id}/episodes/{episodeId}") {
            val user = call.mutatingUser() ?: return@put
            val view = call.staticPlaylistFor(user) ?: return@put
            val position = call.receiveFields()?.intField("position")
            if (position == null || position < 0) {
                return@put call.respondProblem(HttpStatusCode.UnprocessableEntity, "Invalid position", listOf(FieldError("position", "0 or more")))
            }
            if (!playlists.move(view.id, call.parameters["episodeId"]!!, position)) return@put call.respondNotFound("Episode in playlist")
            if (call.isHtmx) return@put call.respondChanged("playlists-changed")
            call.respondPlaylist(view.id, user)
        }

        delete("/playlists/{id}/episodes/{episodeId}") {
            val user = call.mutatingUser() ?: return@delete
            val view = call.staticPlaylistFor(user) ?: return@delete
            if (!playlists.remove(view.id, call.parameters["episodeId"]!!)) return@delete call.respondNotFound("Episode in playlist")
            call.respondChanged("playlists-changed")
        }

        post("/playlists/{id}/shuffle") {
            val user = call.mutatingUser() ?: return@post
            val view = call.playlistFor(user, Action.MODIFY) ?: return@post
            if (view.rules?.order != PlaylistOrder.RANDOM) {
                return@post call.respondProblem(HttpStatusCode.Conflict, "Only a dynamic playlist in random order is shuffled")
            }
            playlists.shuffle(view.id)
            if (call.isHtmx) return@post call.respondChanged("playlists-changed")
            call.respondPlaylist(view.id, user)
        }
    }
}

private data class PlaylistRequest(val title: String, val description: String?, val kind: PlaylistKind, val rules: PlaylistRules)

/**
 * Title, description, kind and rules, checked (ADR 0010). For a change ([current]), absent fields
 * keep their value and the kind stays. Null after a 422.
 */
private suspend fun ApplicationCall.receivePlaylistRequest(current: Playlist?): PlaylistRequest? {
    val fields = receiveFields() ?: emptyMap()
    val base = current?.rules ?: PlaylistRules()
    val errors = mutableListOf<FieldError>()

    val title = (fields.stringField("title") ?: current?.title)?.trim().orEmpty()
    if (!PlaylistLimits.validTitle(title)) errors += FieldError("title", "1 to ${PlaylistLimits.MAX_TITLE_LENGTH} characters")
    val description = if (fields.containsKey("description")) fields.stringField("description")?.trim()?.ifEmpty { null } else current?.description
    if ((description?.length ?: 0) > PlaylistLimits.MAX_DESCRIPTION_LENGTH) {
        errors += FieldError("description", "at most ${PlaylistLimits.MAX_DESCRIPTION_LENGTH} characters")
    }
    val kind = current?.kind ?: fields.enumField<PlaylistKind>("kind", errors) ?: PlaylistKind.STATIC

    /** The field's integer, [default] when absent; a value that is not an integer is an error. */
    fun int(name: String, default: Int): Int {
        if (!fields.hasField(name)) return default
        return fields.intField(name) ?: default.also { errors += FieldError(name, "an integer") }
    }
    val rules = PlaylistRules(
        keywords = fields.keywords() ?: base.keywords,
        favoritesOnly = fields.booleanField("favoritesOnly") ?: base.favoritesOnly,
        minRating = int("minRating", base.minRating),
        unplayedOnly = fields.booleanField("unplayedOnly") ?: base.unplayedOnly,
        order = fields.enumField<PlaylistOrder>("order", errors) ?: base.order,
        limit = int("limit", base.limit),
    )
    errors += PlaylistLimits.invalidFields(rules).map { FieldError(it, if (it == "keywords") "at most 10 keywords of 50 characters" else "out of range") }

    if (errors.isNotEmpty()) {
        respondProblem(HttpStatusCode.UnprocessableEntity, "Invalid playlist", errors)
        return null
    }
    return PlaylistRequest(title, description, kind, rules)
}

/** Keywords separated by commas (forms, templates) or an array; null when absent. */
private fun Map<String, JsonElement>.keywords(): List<String>? = when (val value = get("keywords")) {
    is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.content }.flatMap(PlaylistRules::parseKeywords)
    is JsonPrimitive -> if (value.isString) PlaylistRules.parseKeywords(value.content) else null
    else -> null
}

/** The value of an enumeration field; a wrong value is added to [errors]. Null when absent or blank. */
private inline fun <reified T : Enum<T>> Map<String, JsonElement>.enumField(name: String, errors: MutableList<FieldError>): T? {
    val value = stringField(name)?.takeIf { it.isNotBlank() } ?: return null
    return enumValues<T>().firstOrNull { it.name == value } ?: run {
        errors += FieldError(name, enumValues<T>().joinToString(" or ") { it.name })
        null
    }
}
