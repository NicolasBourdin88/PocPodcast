package fr.podcast.server.api

import fr.podcast.server.ServerServices
import fr.podcast.server.auth.BEARER_AUTH
import fr.podcast.server.auth.SESSION_AUTH
import fr.podcast.server.catalog.UserCatalog
import fr.podcast.server.hal.queueResource
import fr.podcast.server.http.FieldError
import fr.podcast.server.http.isHtmx
import fr.podcast.server.http.representation
import fr.podcast.server.http.respondProblem
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put

/** The user's queue, "up next" (ADR 0008): GET /queue, POST /queue, PUT and DELETE /queue/{episodeId}. */
fun Route.queueRoutes(services: ServerServices) {
    fun catalog(call: ApplicationCall) = UserCatalog(services.database, call.user.id)

    /** The queue after a change; htmx gets 204 and reloads what shows the queue and the episodes. */
    suspend fun ApplicationCall.respondQueue(catalog: UserCatalog) {
        if (isHtmx) {
            response.header("HX-Trigger", "queue-changed, episodes-changed")
            return respond(HttpStatusCode.NoContent)
        }
        val representation = representation()
        respondResource(queueResource(catalog.queue(), representation.forms, services.audioLinks), representation)
    }

    authenticate(SESSION_AUTH, BEARER_AUTH) {
        get("/queue") {
            val representation = call.representation()
            call.respondResource(queueResource(catalog(call).queue(), representation.forms, services.audioLinks), representation)
        }

        post("/queue") {
            call.mutatingUser() ?: return@post
            val fields = call.receiveFields() ?: emptyMap()
            val episodeId = fields.stringField("episodeId") ?: fields.intField("episodeId")?.toString()
            val position = fields.intField("position")
            val errors = buildList {
                if (episodeId.isNullOrBlank()) add(FieldError("episodeId", "required"))
                if (fields.hasField("position") && (position == null || position < 0)) add(FieldError("position", "integer >= 0 expected"))
            }
            if (errors.isNotEmpty()) return@post call.respondProblem(HttpStatusCode.UnprocessableEntity, "Invalid queue request", errors)
            val catalog = catalog(call)
            if (!catalog.enqueue(episodeId!!, position)) {
                return@post call.respondProblem(
                    HttpStatusCode.UnprocessableEntity, "Unknown episode",
                    listOf(FieldError("episodeId", "no episode $episodeId")),
                )
            }
            call.respondQueue(catalog)
        }

        delete("/queue") {
            call.mutatingUser() ?: return@delete
            catalog(call).clearQueue()
            call.respondChanged("queue-changed, episodes-changed")
        }

        put("/queue/{id}") {
            call.mutatingUser() ?: return@put
            val position = call.receiveFields()?.intField("position")
            if (position == null || position < 0) {
                return@put call.respondProblem(
                    HttpStatusCode.UnprocessableEntity, "Invalid move",
                    listOf(FieldError("position", "integer >= 0 expected")),
                )
            }
            val catalog = catalog(call)
            if (!catalog.moveInQueue(call.parameters["id"]!!, position)) return@put call.respondNotFound("Queued episode")
            call.respondQueue(catalog)
        }

        delete("/queue/{id}") {
            call.mutatingUser() ?: return@delete
            if (!catalog(call).dequeue(call.parameters["id"]!!)) return@delete call.respondNotFound("Queued episode")
            call.respondChanged("queue-changed, episodes-changed")
        }
    }
}
