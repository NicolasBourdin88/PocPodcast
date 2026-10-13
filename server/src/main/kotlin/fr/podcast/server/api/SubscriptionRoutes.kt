package fr.podcast.server.api

import fr.podcast.server.ServerServices
import fr.podcast.server.auth.BEARER_AUTH
import fr.podcast.server.auth.SESSION_AUTH
import fr.podcast.server.catalog.UserCatalog
import fr.podcast.server.feeds.FeedException
import fr.podcast.server.hal.subscriptionCollection
import fr.podcast.server.hal.subscriptionResource
import fr.podcast.server.http.FieldError
import fr.podcast.server.http.ProblemTypes
import fr.podcast.server.http.representation
import fr.podcast.server.http.respondProblem
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
import kotlinx.serialization.json.JsonNull

private const val MAX_NOTE_LENGTH = 2000

/** The user's subscriptions: list, subscribe by feed address, annotate, unsubscribe. */
fun Route.subscriptionRoutes(services: ServerServices) {
    fun catalog(call: ApplicationCall) = UserCatalog(services.database, call.user.id)

    authenticate(SESSION_AUTH, BEARER_AUTH) {
        get("/subscriptions") {
            val representation = call.representation()
            call.respondResource(subscriptionCollection(catalog(call).subscriptionList(), representation.forms), representation)
        }

        post("/subscriptions") {
            call.mutatingUser() ?: return@post
            val feedUrl = call.receiveFields()?.stringField("feedUrl")?.trim()
            if (feedUrl.isNullOrEmpty()) {
                return@post call.respondProblem(HttpStatusCode.UnprocessableEntity, "Feed address expected", listOf(FieldError("feedUrl", "required")))
            }
            // The feed is read now: a podcast only exists if its feed could be read.
            val podcastId = try {
                services.refresher.podcastFor(withScheme(feedUrl))
            } catch (refused: FeedException) {
                return@post call.respondFeedProblem(refused)
            }
            val (item, created) = catalog(call).subscribe(podcastId)
            call.response.header(HttpHeaders.Location, "/subscriptions/${item.subscription.id}")
            val representation = call.representation()
            call.respondResource(
                subscriptionResource(item, representation.forms), representation,
                if (created) HttpStatusCode.Created else HttpStatusCode.OK,
            )
        }

        get("/subscriptions/{id}") {
            val item = catalog(call).subscription(call.parameters["id"]!!) ?: return@get call.respondNotFound("Subscription")
            val representation = call.representation()
            call.respondResource(subscriptionResource(item, representation.forms), representation)
        }

        // Partial update: absent fields keep their value, "note": null removes the note.
        patch("/subscriptions/{id}") {
            call.mutatingUser() ?: return@patch
            val catalog = catalog(call)
            val current = catalog.subscription(call.parameters["id"]!!) ?: return@patch call.respondNotFound("Subscription")
            val fields = call.receiveFields() ?: emptyMap()
            val note = when {
                "note" !in fields -> current.subscription.note
                fields["note"] is JsonNull -> null
                else -> fields.stringField("note")?.trim()?.ifEmpty { null }
            }
            val autoDownload = if ("autoDownload" in fields) fields.booleanField("autoDownload") else current.subscription.autoDownload
            val errors = buildList {
                if ("note" in fields && fields["note"] !is JsonNull && fields.stringField("note") == null) add(FieldError("note", "string or null expected"))
                if ((note?.length ?: 0) > MAX_NOTE_LENGTH) add(FieldError("note", "at most $MAX_NOTE_LENGTH characters"))
                if (autoDownload == null) add(FieldError("autoDownload", "boolean expected"))
            }
            if (errors.isNotEmpty()) return@patch call.respondProblem(HttpStatusCode.UnprocessableEntity, "Invalid subscription", errors)

            val updated = catalog.updateSubscription(current.subscription.id, note, autoDownload!!)!!
            services.onSubscriptionChanged(updated.podcast.id.toLong())
            val representation = call.representation()
            call.respondResource(subscriptionResource(updated, representation.forms), representation)
        }

        delete("/subscriptions/{id}") {
            call.mutatingUser() ?: return@delete
            val orphan = catalog(call).unsubscribe(call.parameters["id"]!!) ?: return@delete call.respondNotFound("Subscription")
            // Nobody follows the podcast any more: it goes, with its episodes and files.
            if (orphan != 0L) services.removePodcast(orphan)
            call.respondChanged("subscriptions-changed")
        }
    }
}

/** Users often paste "example.org/feed": HTTPS is assumed when the scheme is missing. */
internal fun withScheme(url: String): String = if ("://" in url) url else "https://$url"

internal suspend fun ApplicationCall.respondFeedProblem(refused: FeedException) {
    val type = when (refused) {
        is FeedException.InvalidUrl -> ProblemTypes.INVALID_FEED_URL
        is FeedException.ForbiddenAddress -> ProblemTypes.FORBIDDEN_FEED_ADDRESS
        is FeedException.Unreachable -> ProblemTypes.FEED_UNREACHABLE
        is FeedException.NotAFeed -> ProblemTypes.NOT_A_FEED
        is FeedException.TooLarge -> ProblemTypes.FEED_TOO_LARGE
    }
    respondProblem(HttpStatusCode.UnprocessableEntity, refused.message, listOf(FieldError("feedUrl", type.substringAfterLast('/'))), type)
}
