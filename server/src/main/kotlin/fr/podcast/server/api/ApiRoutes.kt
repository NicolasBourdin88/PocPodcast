package fr.podcast.server.api

import fr.podcast.server.ServerConfig
import fr.podcast.server.auth.Accounts
import fr.podcast.server.auth.BEARER_AUTH
import fr.podcast.server.auth.SESSION_AUTH
import fr.podcast.server.auth.SessionPrincipal
import fr.podcast.server.auth.TokenPrincipal
import fr.podcast.server.auth.UserAccount
import fr.podcast.server.auth.authPrincipal
import fr.podcast.server.catalog.UserCatalog
import fr.podcast.server.db.ServerDatabase
import fr.podcast.server.hal.episodeCollection
import fr.podcast.server.hal.episodeResource
import fr.podcast.server.hal.podcastResource
import fr.podcast.server.hal.rootResource
import fr.podcast.server.hal.subscriptionCollection
import fr.podcast.server.hal.subscriptionResource
import fr.podcast.server.hal.tokenResource
import fr.podcast.server.hal.userResource
import fr.podcast.server.html.homePage
import fr.podcast.server.http.FieldError
import fr.podcast.server.http.HalJson
import fr.podcast.server.http.Representation
import fr.podcast.server.http.isHtmx
import fr.podcast.server.http.representation
import fr.podcast.server.http.respondProblem
import fr.podcast.server.http.respondSeeOther
import fr.podcast.server.http.respondUnauthenticated
import fr.podcast.server.web.language
import fr.podcast.shared.home.LoadHomeState
import fr.podcast.shared.model.EpisodeStatus
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

private const val DEFAULT_LIMIT = 20
private const val MAX_LIMIT = 100

/** Root, auth and catalog resources (server/src/main/resources/openapi/podcast-api.yaml). */
fun Route.apiRoutes(config: ServerConfig, database: ServerDatabase, accounts: Accounts) {
    fun ApplicationCall.catalog(user: UserAccount) = UserCatalog(database, user.id)

    authenticate(SESSION_AUTH, BEARER_AUTH, optional = true) {
        get("/") {
            val user = call.authPrincipal?.user
            when (val representation = call.representation()) {
                Representation.Html -> {
                    if (user == null) return@get call.respondSeeOther("/login")
                    val home = LoadHomeState(call.catalog(user))()
                    call.respondHtml { homePage(home, call.language(), user) }
                }
                else -> {
                    val home = user?.let { LoadHomeState(call.catalog(it))() }
                    call.respondResource(rootResource(config.name, user, home, representation.forms), representation)
                }
            }
        }
    }

    post("/auth/tokens") {
        val body = call.receiveJsonObject()
        val username = body?.string("username")
        val password = body?.string("password")
        val deviceName = body?.string("deviceName")
        val errors = buildList {
            if (username.isNullOrBlank()) add(FieldError("username", "required"))
            if (password.isNullOrEmpty()) add(FieldError("password", "required"))
            if (deviceName.isNullOrBlank()) add(FieldError("deviceName", "required"))
            else if (deviceName.length > 100) add(FieldError("deviceName", "at most 100 characters"))
        }
        if (errors.isNotEmpty()) return@post call.respondProblem(HttpStatusCode.UnprocessableEntity, "Invalid token request", errors)

        val user = accounts.authenticate(username!!, password!!)
            ?: return@post call.respondProblem(HttpStatusCode.Unauthorized, "Wrong username or password")
        val token = accounts.issueToken(user, deviceName!!)
        call.respondText(tokenResource(token).toString(), HalJson, HttpStatusCode.Created)
    }

    authenticate(SESSION_AUTH, BEARER_AUTH) {
        delete("/auth/tokens/current") {
            val principal = call.authPrincipal as? TokenPrincipal ?: return@delete call.respondUnauthenticated()
            accounts.revokeToken(principal.token)
            call.respond(HttpStatusCode.NoContent)
        }

        get("/me") {
            val representation = call.representation()
            call.respondResource(userResource(call.user, representation.forms), representation)
        }

        get("/subscriptions") {
            call.respondResource(subscriptionCollection(call.catalog(call.user).subscriptionList()), call.representation())
        }

        get("/subscriptions/{id}") {
            val subscription = call.catalog(call.user).subscription(call.parameters["id"]!!)
                ?: return@get call.respondNotFound("Subscription")
            call.respondResource(subscriptionResource(subscription), call.representation())
        }

        get("/podcasts/{id}") {
            val podcast = call.catalog(call.user).podcast(call.parameters["id"]!!) ?: return@get call.respondNotFound("Podcast")
            call.respondResource(podcastResource(podcast), call.representation())
        }

        get("/podcasts/{id}/episodes") {
            val paging = call.paging() ?: return@get
            val podcastId = call.parameters["id"]!!
            val page = call.catalog(call.user).podcastEpisodes(podcastId, paging.limit, paging.offset)
                ?: return@get call.respondNotFound("Podcast")
            val representation = call.representation()
            call.respondResource(episodeCollection(page, "/podcasts/$podcastId/episodes", emptyMap(), representation.forms), representation)
        }

        get("/episodes") {
            val paging = call.paging() ?: return@get
            val statusParameter = call.request.queryParameters["status"]
            val status = statusParameter?.let { value -> EpisodeStatus.entries.firstOrNull { it.name == value } }
            if (statusParameter != null && status == null) {
                return@get call.respondProblem(HttpStatusCode.BadRequest, "status must be one of ${EpisodeStatus.entries.joinToString()}")
            }
            val page = call.catalog(call.user).latest(status, paging.limit, paging.offset)
            val query = status?.let { mapOf("status" to it.name) } ?: emptyMap()
            val representation = call.representation()
            call.respondResource(episodeCollection(page, "/episodes", query, representation.forms), representation)
        }

        get("/episodes/{id}") {
            val item = call.catalog(call.user).episodeItem(call.parameters["id"]!!) ?: return@get call.respondNotFound("Episode")
            val representation = call.representation()
            call.respondResource(episodeResource(item, representation.forms), representation)
        }

        put("/episodes/{id}/progress") {
            val user = call.mutatingUser() ?: return@put
            val catalog = call.catalog(user)
            val episode = catalog.episode(call.parameters["id"]!!) ?: return@put call.respondNotFound("Episode")
            val position = call.receiveJsonObject()?.get("positionSeconds") as? JsonPrimitive
            val seconds = position?.takeUnless { it.isString }?.intOrNull
            if (seconds == null || seconds !in 0..episode.durationSeconds) {
                return@put call.respondProblem(
                    HttpStatusCode.UnprocessableEntity, "Invalid progress",
                    listOf(FieldError("positionSeconds", "integer between 0 and ${episode.durationSeconds} expected")),
                )
            }
            catalog.updateProgress(episode.id, seconds)
            call.respondEpisodesChanged()
        }

        post("/episodes/{id}/played") {
            val user = call.mutatingUser() ?: return@post
            val catalog = call.catalog(user)
            val episode = catalog.episode(call.parameters["id"]!!) ?: return@post call.respondNotFound("Episode")
            catalog.markPlayed(episode.id)
            call.respondEpisodesChanged()
        }

        delete("/episodes/{id}/played") {
            val user = call.mutatingUser() ?: return@delete
            val catalog = call.catalog(user)
            val episode = catalog.episode(call.parameters["id"]!!) ?: return@delete call.respondNotFound("Episode")
            catalog.markUnplayed(episode.id)
            call.respondEpisodesChanged()
        }

        get("/episodes/{id}/audio") {
            val audioUrl = call.catalog(call.user).audioUrl(call.parameters["id"]!!) ?: return@get call.respondNotFound("Episode")
            call.response.header(HttpHeaders.Location, audioUrl)
            call.respond(HttpStatusCode.Found)
        }
    }
}

private val ApplicationCall.user: UserAccount get() = authPrincipal!!.user

private val Representation.forms: Boolean get() = this == Representation.HalForms

/** JSON resources; a browser asking for HTML gets HAL until the thin client has that page. */
private suspend fun ApplicationCall.respondResource(resource: JsonObject, representation: Representation) {
    val contentType = if (representation == Representation.Html) HalJson else representation.contentType
    respondText(resource.toString(), contentType)
}

private suspend fun ApplicationCall.respondNotFound(what: String) =
    respondProblem(HttpStatusCode.NotFound, "$what ${parameters["id"]} not found")

/** 204; htmx also gets a trigger so the page reloads what changed. */
private suspend fun ApplicationCall.respondEpisodesChanged() {
    if (isHtmx) response.header("HX-Trigger", "episodes-changed")
    respond(HttpStatusCode.NoContent)
}

/**
 * The user, for a request that changes state. Session requests must come from htmx:
 * a cross-site form cannot set the HX-Request header (CSRF, ADR 0005).
 */
private suspend fun ApplicationCall.mutatingUser(): UserAccount? {
    val principal = authPrincipal!!
    if (principal is SessionPrincipal && !isHtmx) {
        respondProblem(HttpStatusCode.Forbidden, "Requests authenticated by session must be sent by htmx (HX-Request)")
        return null
    }
    return principal.user
}

private data class Paging(val offset: Int, val limit: Int)

private suspend fun ApplicationCall.paging(): Paging? {
    val offsetParameter = request.queryParameters["offset"]
    val limitParameter = request.queryParameters["limit"]
    val offset = offsetParameter?.toIntOrNull() ?: if (offsetParameter == null) 0 else -1
    val limit = limitParameter?.toIntOrNull() ?: if (limitParameter == null) DEFAULT_LIMIT else -1
    if (offset < 0 || limit !in 1..MAX_LIMIT) {
        respondProblem(HttpStatusCode.BadRequest, "offset must be >= 0 and limit between 1 and $MAX_LIMIT")
        return null
    }
    return Paging(offset, limit)
}

private suspend fun ApplicationCall.receiveJsonObject(): JsonObject? =
    runCatching { Json.parseToJsonElement(receiveText()).jsonObject }.getOrNull()

private fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
