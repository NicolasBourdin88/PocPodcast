package fr.podcast.server.api

import fr.podcast.server.ServerServices
import fr.podcast.server.auth.BEARER_AUTH
import fr.podcast.server.auth.SESSION_AUTH
import fr.podcast.server.auth.TokenPrincipal
import fr.podcast.server.auth.authPrincipal
import fr.podcast.server.catalog.UserCatalog
import fr.podcast.server.hal.episodeCollection
import fr.podcast.server.hal.episodeResource
import fr.podcast.server.hal.podcastResource
import fr.podcast.server.hal.rootResource
import fr.podcast.server.hal.tokenResource
import fr.podcast.server.html.homePage
import fr.podcast.server.http.FieldError
import fr.podcast.server.http.HalJson
import fr.podcast.server.http.ProblemTypes
import fr.podcast.server.media.AudioLinks
import fr.podcast.server.http.Representation
import fr.podcast.server.http.isHtmx
import fr.podcast.server.http.representation
import fr.podcast.server.http.respondProblem
import fr.podcast.server.http.respondSeeOther
import fr.podcast.server.http.respondUnauthenticated
import fr.podcast.server.web.language
import fr.podcast.shared.home.LoadHomeState
import fr.podcast.shared.model.EpisodeStatus
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.http.content.LocalPathContent
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.html.respondHtml
import io.ktor.server.plugins.origin
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Root, auth, podcast and episode resources (server/src/main/resources/openapi/podcast-api.yaml). */
fun Route.apiRoutes(services: ServerServices) {
    val accounts = services.accounts
    fun catalog(call: ApplicationCall) = UserCatalog(services.database, call.user.id)

    authenticate(SESSION_AUTH, BEARER_AUTH, optional = true) {
        get("/") {
            val user = call.authPrincipal?.user
            when (val representation = call.representation()) {
                Representation.Html -> {
                    if (user == null) return@get call.respondSeeOther("/login")
                    val catalog = UserCatalog(services.database, user.id)
                    val home = LoadHomeState(catalog)()
                    val queue = catalog.queue()
                    val others = accounts.users().filter { it.id != user.id }
                    call.respondHtml { homePage(home, queue, others, call.language(), user, services.audioLinks) }
                }
                else -> {
                    val home = user?.let { LoadHomeState(UserCatalog(services.database, it.id))() }
                    call.respondResource(rootResource(services.config.name, user, home, representation.forms, services.audioLinks), representation)
                }
            }
        }

        // Players cannot send credentials: a signed link (ADR 0008) or the session/token gives access.
        // The server's copy when it has one (with byte ranges for seeking), the original file otherwise.
        get("/episodes/{id}/audio") {
            val episodeId = call.parameters["id"]!!
            val parameters = call.request.queryParameters
            when (services.audioLinks.check(episodeId, parameters["expires"], parameters["signature"])) {
                AudioLinks.Check.Valid -> Unit
                AudioLinks.Check.Expired -> return@get call.respondProblem(
                    HttpStatusCode.Forbidden, "This audio link has expired: read the episode again for a new one",
                    type = ProblemTypes.AUDIO_LINK_EXPIRED,
                )
                AudioLinks.Check.Invalid -> if (call.authPrincipal == null) {
                    call.response.header(HttpHeaders.WWWAuthenticate, "Bearer realm=\"podcast\"")
                    return@get call.respondProblem(HttpStatusCode.Unauthorized, "Signed link or authentication required")
                }
            }
            val audioUrl = services.database.query { catalogQueries.episodeAudio(episodeId.toLongOrNull() ?: -1).executeAsOneOrNull() }
                ?: return@get call.respondNotFound("Episode")
            val file = services.media.file(episodeId.toLong())
            if (file != null) {
                val (path, mimeType) = file
                return@get call.respond(LocalPathContent(path, ContentType.parse(mimeType)))
            }
            call.response.header(HttpHeaders.Location, audioUrl)
            call.respond(HttpStatusCode.Found)
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

        val address = call.request.origin.remoteAddress
        services.loginThrottle.retryAfterSeconds(username!!, address)?.let { return@post call.respondTooManyAttempts(it) }
        val user = accounts.authenticate(username, password!!)
        if (user == null) {
            services.loginThrottle.failed(username, address)
            return@post call.respondProblem(HttpStatusCode.Unauthorized, "Wrong username or password")
        }
        services.loginThrottle.succeeded(username, address)
        val token = accounts.issueToken(user, deviceName!!)
        call.respondText(tokenResource(token).toString(), HalJson, HttpStatusCode.Created)
    }

    authenticate(SESSION_AUTH, BEARER_AUTH) {
        delete("/auth/tokens/current") {
            val principal = call.authPrincipal as? TokenPrincipal ?: return@delete call.respondUnauthenticated()
            accounts.revokeToken(principal.token)
            call.respond(HttpStatusCode.NoContent)
        }

        get("/podcasts/{id}") {
            val podcast = catalog(call).podcast(call.parameters["id"]!!) ?: return@get call.respondNotFound("Podcast")
            val representation = call.representation()
            call.respondResource(podcastResource(podcast, representation.forms), representation)
        }

        post("/podcasts/{id}/refresh") {
            call.mutatingUser() ?: return@post
            val catalog = catalog(call)
            val podcast = catalog.podcast(call.parameters["id"]!!) ?: return@post call.respondNotFound("Podcast")
            services.refresher.refresh(podcast.id.toLong())
            val representation = call.representation()
            call.respondResource(podcastResource(catalog.podcast(podcast.id)!!, representation.forms), representation)
        }

        get("/podcasts/{id}/episodes") {
            val paging = call.paging() ?: return@get
            val podcastId = call.parameters["id"]!!
            val page = catalog(call).podcastEpisodes(podcastId, paging.limit, paging.offset)
                ?: return@get call.respondNotFound("Podcast")
            val representation = call.representation()
            call.respondResource(episodeCollection(page, "/podcasts/$podcastId/episodes", emptyMap(), representation.forms, services.audioLinks), representation)
        }

        get("/episodes") {
            val paging = call.paging() ?: return@get
            val statusParameter = call.request.queryParameters["status"]
            val status = statusParameter?.let { value -> EpisodeStatus.entries.firstOrNull { it.name == value } }
            if (statusParameter != null && status == null) {
                return@get call.respondProblem(HttpStatusCode.BadRequest, "status must be one of ${EpisodeStatus.entries.joinToString()}")
            }
            val page = catalog(call).latest(status, paging.limit, paging.offset)
            val query = status?.let { mapOf("status" to it.name) } ?: emptyMap()
            val representation = call.representation()
            call.respondResource(episodeCollection(page, "/episodes", query, representation.forms, services.audioLinks), representation)
        }

        get("/episodes/{id}") {
            val item = catalog(call).episodeItem(call.parameters["id"]!!) ?: return@get call.respondNotFound("Episode")
            val representation = call.representation()
            call.respondResource(episodeResource(item, representation.forms, services.audioLinks), representation)
        }

        put("/episodes/{id}/progress") {
            call.mutatingUser() ?: return@put
            val catalog = catalog(call)
            val episode = catalog.episode(call.parameters["id"]!!) ?: return@put call.respondNotFound("Episode")
            val position = call.receiveJsonObject()?.get("positionSeconds") as? JsonPrimitive
            val seconds = position?.takeUnless { it.isString }?.intOrNull
            // Not bounded by the feed's duration, which is often approximate (or 0 when unknown).
            if (seconds == null || seconds !in 0..MAX_POSITION_SECONDS) {
                return@put call.respondProblem(
                    HttpStatusCode.UnprocessableEntity, "Invalid progress",
                    listOf(FieldError("positionSeconds", "integer between 0 and $MAX_POSITION_SECONDS expected")),
                )
            }
            catalog.updateProgress(episode.id, seconds)
            call.respondChanged()
        }

        post("/episodes/{id}/played") {
            call.mutatingUser() ?: return@post
            val catalog = catalog(call)
            val episode = catalog.episode(call.parameters["id"]!!) ?: return@post call.respondNotFound("Episode")
            catalog.markPlayed(episode.id)
            call.respondChanged()
        }

        delete("/episodes/{id}/played") {
            call.mutatingUser() ?: return@delete
            val catalog = catalog(call)
            val episode = catalog.episode(call.parameters["id"]!!) ?: return@delete call.respondNotFound("Episode")
            catalog.markUnplayed(episode.id)
            call.respondChanged()
        }

        put("/episodes/{id}/kept") {
            call.mutatingUser() ?: return@put
            val catalog = catalog(call)
            val episode = catalog.episode(call.parameters["id"]!!) ?: return@put call.respondNotFound("Episode")
            catalog.setKept(episode.id, kept = true)
            call.respondChanged()
        }

        delete("/episodes/{id}/kept") {
            call.mutatingUser() ?: return@delete
            val catalog = catalog(call)
            val episode = catalog.episode(call.parameters["id"]!!) ?: return@delete call.respondNotFound("Episode")
            catalog.setKept(episode.id, kept = false)
            call.respondChanged()
        }

        put("/episodes/{id}/favorite") {
            call.mutatingUser() ?: return@put
            val catalog = catalog(call)
            val episode = catalog.episode(call.parameters["id"]!!) ?: return@put call.respondNotFound("Episode")
            catalog.setFavorite(episode.id, favorite = true)
            call.respondChanged()
        }

        delete("/episodes/{id}/favorite") {
            call.mutatingUser() ?: return@delete
            val catalog = catalog(call)
            val episode = catalog.episode(call.parameters["id"]!!) ?: return@delete call.respondNotFound("Episode")
            catalog.setFavorite(episode.id, favorite = false)
            call.respondChanged()
        }

        put("/episodes/{id}/rating") {
            call.mutatingUser() ?: return@put
            val catalog = catalog(call)
            val episode = catalog.episode(call.parameters["id"]!!) ?: return@put call.respondNotFound("Episode")
            val rating = call.receiveFields()?.intField("rating")
            if (rating == null || rating !in 1..5) {
                return@put call.respondProblem(HttpStatusCode.UnprocessableEntity, "A rating from 1 to 5 expected", listOf(FieldError("rating", "from 1 to 5")))
            }
            catalog.setRating(episode.id, rating)
            call.respondChanged()
        }

        delete("/episodes/{id}/rating") {
            call.mutatingUser() ?: return@delete
            val catalog = catalog(call)
            val episode = catalog.episode(call.parameters["id"]!!) ?: return@delete call.respondNotFound("Episode")
            catalog.setRating(episode.id, null)
            call.respondChanged()
        }

        post("/episodes/{id}/download") {
            call.mutatingUser() ?: return@post
            val episode = catalog(call).episode(call.parameters["id"]!!) ?: return@post call.respondNotFound("Episode")
            services.media.request(episode.id.toLong())
            if (call.isHtmx) call.response.header("HX-Trigger", "episodes-changed")
            call.respond(HttpStatusCode.Accepted)
        }

    }
}

/** A day: more than any episode, and a bound for the integer the clients send. */
private const val MAX_POSITION_SECONDS = 24 * 3600
