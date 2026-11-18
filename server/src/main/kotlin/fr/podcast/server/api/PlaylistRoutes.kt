package fr.podcast.server.api

import fr.podcast.server.ServerServices
import fr.podcast.server.auth.Action
import fr.podcast.server.auth.BEARER_AUTH
import fr.podcast.server.auth.SESSION_AUTH
import fr.podcast.server.auth.UserAccount
import fr.podcast.server.auth.can
import fr.podcast.server.catalog.PlaylistView
import fr.podcast.server.feeds.Enclosure
import fr.podcast.server.feeds.PlaylistFeed
import fr.podcast.server.hal.playlistCollection
import fr.podcast.server.hal.playlistResource
import fr.podcast.server.html.PlaylistPage
import fr.podcast.server.html.playlistPage
import fr.podcast.server.html.playlistsPage
import fr.podcast.server.http.Representation
import fr.podcast.server.web.language
import fr.podcast.server.http.FieldError
import fr.podcast.server.http.isHtmx
import fr.podcast.server.http.representation
import fr.podcast.server.http.respondProblem
import fr.podcast.shared.model.Playlist
import fr.podcast.shared.model.PlaylistKind
import fr.podcast.shared.model.PlaylistLimits
import fr.podcast.shared.model.PlaylistOrder
import fr.podcast.shared.model.PlaylistRules
import fr.podcast.shared.model.ShareType
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.html.respondHtml
import io.ktor.server.plugins.origin
import io.ktor.server.response.header
import io.ktor.server.response.respondText
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
            if (representation == Representation.Html) {
                val own = playlists.playlistsOf(user)
                val shared = services.sharing.received(user).filter { it.playlist != null }
                return@get call.respondHtml { playlistsPage(own, shared, call.language(), user) }
            }
            call.respondResource(playlistCollection(playlists.playlistsOf(user), user, representation.forms, services.audioLinks), representation)
        }

        post("/playlists") {
            val user = call.mutatingUser() ?: return@post
            val request = call.receivePlaylistRequest(current = null) ?: return@post
            val id = playlists.create(user.id, request.title, request.description, request.kind, request.rules)
            if (call.isHtmx) return@post call.respondLocation("/playlists/$id")
            call.response.header(HttpHeaders.Location, "/playlists/$id")
            call.respondPlaylist(id, user, HttpStatusCode.Created)
        }

        get("/playlists/{id}") {
            val user = call.user
            val view = call.visiblePlaylist(user) ?: return@get
            val representation = call.representation()
            if (representation == Representation.Html) {
                val owner = user.can(Action.SHARE, view.ownerId, view.permission)
                val page = PlaylistPage(
                    view = view,
                    episodes = playlists.episodes(view, user.id),
                    canModify = user.can(Action.MODIFY, view.ownerId, view.permission),
                    owner = owner,
                    shares = if (owner) services.sharing.sharesOf(ShareType.PLAYLIST, view.id) else emptyList(),
                    others = if (owner) services.accounts.users().filter { it.id != view.ownerId } else emptyList(),
                    feedUrl = view.feedSecret?.takeIf { owner }?.let { "${call.baseUrl()}/feeds/$it" },
                )
                return@get call.respondHtml { playlistPage(page, call.language(), user, services.audioLinks) }
            }
            call.respondResource(playlistResource(view, user, representation.forms, services.audioLinks, playlists.episodes(view, user.id)), representation)
        }

        patch("/playlists/{id}") {
            val user = call.mutatingUser() ?: return@patch
            val view = call.playlistFor(user, Action.MODIFY) ?: return@patch
            val request = call.receivePlaylistRequest(current = view.playlist) ?: return@patch
            playlists.update(view.id, request.title, request.description, request.rules)
            // The title is also the page's: the page is read again.
            if (call.isHtmx) return@patch call.respondLocation("/playlists/${view.id}")
            call.respondPlaylist(view.id, user)
        }

        delete("/playlists/{id}") {
            val user = call.mutatingUser() ?: return@delete
            val view = call.playlistFor(user, Action.DELETE) ?: return@delete
            playlists.delete(view.id)
            if (call.isHtmx) return@delete call.respondLocation("/playlists")
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
            if (call.isHtmx) return@post call.respondNotice(call.language().strings.addedTo(view.playlist.title), "playlists-changed")
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

        // The secret address of its RSS feed: published or replaced, then withdrawn, by its owner (ADR 0010).
        put("/playlists/{id}/feed") {
            val user = call.mutatingUser() ?: return@put
            val view = call.playlistFor(user, Action.SHARE) ?: return@put
            playlists.publishFeed(view.id)
            if (call.isHtmx) return@put call.respondChanged("playlists-changed")
            call.respondPlaylist(view.id, user)
        }

        delete("/playlists/{id}/feed") {
            val user = call.mutatingUser() ?: return@delete
            val view = call.playlistFor(user, Action.SHARE) ?: return@delete
            playlists.unpublishFeed(view.id)
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

/**
 * The RSS feeds of playlists, without authentication: their secret address is the access, as podcast
 * apps cannot sign in (ADR 0005, 0010). A dynamic playlist is computed as its owner sees it.
 */
fun Route.playlistFeedRoutes(services: ServerServices) {
    val playlists = services.playlists

    get("/feeds/{secret}") {
        val secret = call.parameters["secret"]!!
        val view = playlists.byFeedSecret(secret) ?: return@get call.respondNotFound("Feed")
        val base = call.baseUrl()
        val items = playlists.episodes(view, view.ownerId).mapNotNull { item ->
            services.database.query { catalogQueries.episodeEnclosure(item.episode.id.toLong()).executeAsOneOrNull() }
                ?.let { item to Enclosure(it.guid, it.type, it.length) }
        }
        val owner = services.accounts.user(view.ownerId)?.displayName.orEmpty()
        val rss = PlaylistFeed.write(view.playlist, owner, "$base/feeds/$secret", "$base/", items) { "$base/feeds/$secret/episodes/$it" }
        call.respondText(rss, ContentType("application", "rss+xml").withCharset(Charsets.UTF_8))
    }

    // An enclosure: only the episodes of the playlist, so the address opens nothing else.
    get("/feeds/{secret}/episodes/{episodeId}") {
        val view = playlists.byFeedSecret(call.parameters["secret"]!!) ?: return@get call.respondNotFound("Feed")
        val episodeId = call.parameters["episodeId"]!!
        if (playlists.episodes(view, view.ownerId).none { it.episode.id == episodeId }) return@get call.respondNotFound("Episode in feed")
        call.respondEpisodeAudio(services, episodeId)
    }
}

/** The server's address as the client reached it, e.g. http://localhost:8080. */
private fun ApplicationCall.baseUrl(): String {
    val origin = request.origin
    val defaultPort = (origin.scheme == "http" && origin.serverPort == 80) || (origin.scheme == "https" && origin.serverPort == 443)
    return "${origin.scheme}://${origin.serverHost}" + if (defaultPort) "" else ":${origin.serverPort}"
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
