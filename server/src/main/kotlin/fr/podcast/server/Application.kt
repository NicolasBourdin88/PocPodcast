package fr.podcast.server

import fr.podcast.server.hal.HalJson
import fr.podcast.server.hal.episodeCollection
import fr.podcast.server.hal.episodeResource
import fr.podcast.server.hal.homeResource
import fr.podcast.server.hal.podcastCollection
import fr.podcast.server.hal.podcastResource
import fr.podcast.server.html.homePage
import fr.podcast.server.html.latestFragmentHtml
import fr.podcast.shared.home.LoadHomeState
import fr.podcast.shared.i18n.Language
import fr.podcast.shared.repository.FakePodcastRepository
import fr.podcast.shared.repository.PodcastRepository
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.engine.embeddedServer
import io.ktor.server.html.respondHtml
import io.ktor.server.netty.Netty
import io.ktor.server.request.acceptItems
import io.ktor.server.request.acceptLanguageItems
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.JsonObject

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0") { module() }.start(wait = true)
}

fun Application.module(repository: PodcastRepository = FakePodcastRepository()) {
    val loadHomeState = LoadHomeState(repository)

    routing {
        get("/") {
            val state = loadHomeState()
            call.response.header(HttpHeaders.Vary, "${HttpHeaders.Accept}, ${HttpHeaders.AcceptLanguage}")
            if (call.prefersHal()) {
                call.respondHal(homeResource(state))
            } else {
                val language = call.language()
                call.respondHtml { homePage(state, language) }
            }
        }

        get("/fragments/latest") {
            call.response.header(HttpHeaders.Vary, HttpHeaders.AcceptLanguage)
            call.respondText(latestFragmentHtml(loadHomeState().latest, call.language().strings), ContentType.Text.Html)
        }

        get("/podcasts") {
            call.respondHal(podcastCollection(repository.subscriptions()))
        }

        get("/podcasts/{id}") {
            val podcast = repository.podcast(call.parameters["id"]!!)
                ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respondHal(podcastResource(podcast))
        }

        get("/podcasts/{id}/episodes") {
            val podcast = repository.podcast(call.parameters["id"]!!)
                ?: return@get call.respond(HttpStatusCode.NotFound)
            val episodes = repository.latestEpisodes(limit = Int.MAX_VALUE).filter { it.podcastId == podcast.id }
            call.respondHal(episodeCollection(episodes, mapOf(podcast.id to podcast), self = "/podcasts/${podcast.id}/episodes"))
        }

        get("/episodes") {
            val podcasts = repository.subscriptions().associateBy { it.id }
            val episodes = repository.latestEpisodes(limit = Int.MAX_VALUE)
            call.respondHal(episodeCollection(episodes, podcasts))
        }

        get("/episodes/{id}") {
            val podcasts = repository.subscriptions().associateBy { it.id }
            val episode = repository.latestEpisodes(limit = Int.MAX_VALUE).find { it.id == call.parameters["id"] }
                ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respondHal(episodeResource(episode, podcasts[episode.podcastId]))
        }
    }
}

/** Interface language negotiated from Accept-Language (HAL stays language-neutral). */
private fun ApplicationCall.language(): Language =
    Language.negotiate(request.acceptLanguageItems().map { it.value })

/** True when the client ranks a JSON representation above HTML in its Accept header. */
private fun ApplicationCall.prefersHal(): Boolean {
    val best = request.acceptItems()
        .map { ContentType.parse(it.value) }
        .firstOrNull { it.match(ContentType.Text.Html) || it.match(HalJson) || it.match(ContentType.Application.Json) }
    return best != null && !best.match(ContentType.Text.Html) && best != ContentType.Any
}

private suspend fun ApplicationCall.respondHal(resource: JsonObject) {
    respondText(resource.toString(), HalJson)
}
