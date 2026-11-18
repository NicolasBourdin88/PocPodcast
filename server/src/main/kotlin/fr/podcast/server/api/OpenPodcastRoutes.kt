package fr.podcast.server.api

import fr.podcast.server.ServerServices
import fr.podcast.server.http.respondProblem
import fr.podcast.server.sync.OpaLookup
import fr.podcast.server.sync.OpaNewSubscriptions
import fr.podcast.server.sync.OpaSubscription
import fr.podcast.server.sync.OpaUpdate
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.time.OffsetDateTime
import java.time.ZoneOffset

private const val DEFAULT_PER_PAGE = 50
private const val MAX_PER_PAGE = 100

/**
 * The subscriptions of the Open Podcast API v1 (ADR 0011). The specification does not describe
 * authentication yet: a Bearer token, or Basic with the password or an app password, as for gPodder.
 */
fun Route.openPodcastRoutes(services: ServerServices) {
    val api = services.openPodcastApi

    route("/v1") {
        get("/subscriptions") {
            val user = call.syncUser(services, username = null) ?: return@get
            val query = call.request.queryParameters
            val since = query["since"]?.let { parseInstant(it) ?: return@get call.respondProblem(HttpStatusCode.BadRequest, "\"since\" is an ISO 8601 date") }
            val page = query["page"]?.let { it.toIntOrNull()?.takeIf { page -> page >= 1 } ?: return@get call.respondProblem(HttpStatusCode.BadRequest, "\"page\" starts at 1") } ?: 1
            val perPage = query["per_page"]?.let {
                it.toIntOrNull()?.takeIf { size -> size in 1..MAX_PER_PAGE } ?: return@get call.respondProblem(HttpStatusCode.BadRequest, "\"per_page\" is between 1 and $MAX_PER_PAGE")
            } ?: DEFAULT_PER_PAGE
            call.respondSyncJson(syncJson.encodeToString(api.subscriptions(user, since, page, perPage)))
        }

        post("/subscriptions") {
            val user = call.syncUser(services, username = null) ?: return@post
            val request = runCatching { syncJson.decodeFromString<OpaNewSubscriptions>(call.receiveText()) }.getOrNull()
                ?: return@post call.respondProblem(HttpStatusCode.BadRequest, "A list \"subscriptions\" of objects with a \"feed_url\" expected")
            call.respondSyncJson(syncJson.encodeToString(api.add(user, request.subscriptions)))
        }

        get("/subscriptions/{guid}") {
            val user = call.syncUser(services, username = null) ?: return@get
            val entry = call.entry(api.find(user, call.parameters["guid"]!!)) ?: return@get
            call.respondSyncJson(syncJson.encodeToString(entry))
        }

        patch("/subscriptions/{guid}") {
            val user = call.syncUser(services, username = null) ?: return@patch
            val change = runCatching { syncJson.decodeFromString<OpaUpdate>(call.receiveText()) }.getOrNull()
                ?.takeIf { it.new_feed_url != null || it.new_guid != null || it.is_subscribed != null }
                ?: return@patch call.respondProblem(HttpStatusCode.BadRequest, "At least one of new_feed_url, new_guid, is_subscribed expected")
            val entry = call.entry(api.find(user, call.parameters["guid"]!!)) ?: return@patch
            val updated = try {
                api.update(user, entry, change)
            } catch (refused: IllegalArgumentException) {
                return@patch call.respondProblem(HttpStatusCode.BadRequest, refused.message)
            }
            call.respondSyncJson(syncJson.encodeToString(updated))
        }

        delete("/subscriptions/{guid}") {
            val user = call.syncUser(services, username = null) ?: return@delete
            val entry = call.entry(api.find(user, call.parameters["guid"]!!)) ?: return@delete
            call.respondSyncJson(syncJson.encodeToString(api.delete(user, entry)), HttpStatusCode.Accepted)
        }

        get("/deletions/{id}") {
            val user = call.syncUser(services, username = null) ?: return@get
            val status = call.parameters["id"]!!.toLongOrNull()?.let { api.deletion(user, it) }
                ?: return@get call.respondProblem(HttpStatusCode.NotFound, "Deletion ${call.parameters["id"]} not found")
            call.respondSyncJson(syncJson.encodeToString(status))
        }
    }
}

/** The entry found, or null after answering 410 (deleted, with the specification's body) or 404. */
private suspend fun ApplicationCall.entry(lookup: OpaLookup): OpaSubscription? = when (lookup) {
    is OpaLookup.Found -> lookup.subscription
    OpaLookup.Deleted -> null.also { respondSyncJson("""{"code":410,"message":"Subscription has been deleted"}""", HttpStatusCode.Gone) }
    OpaLookup.Unknown -> null.also { respondProblem(HttpStatusCode.NotFound, "Subscription ${parameters["guid"]} not found") }
}

/** ISO 8601, with a zone (UTC when it has none). */
private fun parseInstant(value: String): OffsetDateTime? =
    runCatching { OffsetDateTime.parse(value) }.getOrNull()
        ?: runCatching { java.time.LocalDateTime.parse(value).atOffset(ZoneOffset.UTC) }.getOrNull()
