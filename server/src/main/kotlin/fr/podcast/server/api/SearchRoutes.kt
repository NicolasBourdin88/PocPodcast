package fr.podcast.server.api

import fr.podcast.server.ServerServices
import fr.podcast.server.auth.BEARER_AUTH
import fr.podcast.server.auth.SESSION_AUTH
import fr.podcast.server.catalog.UserCatalog
import fr.podcast.server.hal.searchResults
import fr.podcast.server.html.explorePage
import fr.podcast.server.http.ProblemTypes
import fr.podcast.server.http.Representation
import fr.podcast.server.http.representation
import fr.podcast.server.http.respondProblem
import fr.podcast.server.search.SearchFailedException
import fr.podcast.server.search.normalizedFeed
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.html.respondHtml
import fr.podcast.server.web.language
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** Podcast search, proxied by the server (Apple Podcasts, Podcast Index when configured). */
fun Route.searchRoutes(services: ServerServices) {
    authenticate(SESSION_AUTH, BEARER_AUTH) {
        get("/search") {
            val query = call.request.queryParameters["q"]?.trim().orEmpty()
            val representation = call.representation()
            val subscribed = UserCatalog(services.database, call.user.id).subscribedFeedUrls().map(::normalizedFeed).toSet()
            // The Explore page: without a query it only shows the search form.
            if (representation == Representation.Html) {
                val language = call.language()
                if (query.length !in 2..200) return@get call.respondHtml { explorePage(query, null, subscribed, language, call.user) }
                val outcome = runCatching { services.search.search(query) }.getOrNull()
                val error = if (outcome == null) language.strings.searchFailed else null
                return@get call.respondHtml { explorePage(query, outcome?.hits, subscribed, language, call.user, error) }
            }
            if (query.length !in 2..200) {
                return@get call.respondProblem(HttpStatusCode.BadRequest, "q must be between 2 and 200 characters")
            }
            val outcome = try {
                services.search.search(query)
            } catch (failed: SearchFailedException) {
                return@get call.respondProblem(HttpStatusCode.BadGateway, failed.message, type = ProblemTypes.SEARCH_FAILED)
            }
            call.respondResource(searchResults(query, outcome.hits, outcome.answeredBy, subscribed, representation.forms), representation)
        }
    }
}
