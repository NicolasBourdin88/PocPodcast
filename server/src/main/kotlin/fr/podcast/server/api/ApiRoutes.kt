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
import fr.podcast.server.hal.userResource
import fr.podcast.server.html.homePage
import fr.podcast.server.http.FieldError
import fr.podcast.server.http.HalJson
import fr.podcast.server.http.Representation
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
                    val home = LoadHomeState(UserCatalog(services.database, user.id))()
                    call.respondHtml { homePage(home, call.language(), user) }
                }
                else -> {
                    val home = user?.let { LoadHomeState(UserCatalog(services.database, it.id))() }
                    call.respondResource(rootResource(services.config.name, user, home, representation.forms), representation)
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
            call.respondResource(episodeCollection(page, "/podcasts/$podcastId/episodes", emptyMap(), representation.forms), representation)
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
            call.respondResource(episodeCollection(page, "/episodes", query, representation.forms), representation)
        }

        get("/episodes/{id}") {
            val item = catalog(call).episodeItem(call.parameters["id"]!!) ?: return@get call.respondNotFound("Episode")
            val representation = call.representation()
            call.respondResource(episodeResource(item, representation.forms), representation)
        }

        put("/episodes/{id}/progress") {
            call.mutatingUser() ?: return@put
            val catalog = catalog(call)
            val episode = catalog.episode(call.parameters["id"]!!) ?: return@put call.respondNotFound("Episode")
            val position = call.receiveJsonObject()?.get("positionSeconds") as? JsonPrimitive
            val seconds = position?.takeUnless { it.isString }?.intOrNull
            // Some feeds do not give the duration (0): only the lower bound applies then.
            val maximum = episode.durationSeconds.takeIf { it > 0 } ?: Int.MAX_VALUE
            if (seconds == null || seconds !in 0..maximum) {
                return@put call.respondProblem(
                    HttpStatusCode.UnprocessableEntity, "Invalid progress",
                    listOf(FieldError("positionSeconds", "integer between 0 and the episode duration expected")),
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

        get("/episodes/{id}/audio") {
            val audioUrl = catalog(call).audioUrl(call.parameters["id"]!!) ?: return@get call.respondNotFound("Episode")
            call.response.header(HttpHeaders.Location, audioUrl)
            call.respond(HttpStatusCode.Found)
        }
    }
}
