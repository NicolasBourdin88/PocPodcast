package fr.podcast.server.api

import fr.podcast.server.ServerServices
import fr.podcast.server.auth.BEARER_AUTH
import fr.podcast.server.auth.SESSION_AUTH
import fr.podcast.server.catalog.UserCatalog
import fr.podcast.server.feeds.FeedException
import fr.podcast.server.feeds.code
import fr.podcast.server.feeds.withScheme
import fr.podcast.server.opml.Opml
import fr.podcast.server.opml.OpmlException
import fr.podcast.server.hal.opmlImportResource
import fr.podcast.server.hal.subscriptionCollection
import fr.podcast.server.hal.subscriptionResource
import fr.podcast.server.http.FieldError
import fr.podcast.server.http.HalJson
import fr.podcast.server.http.ProblemTypes
import fr.podcast.server.http.isHtmx
import fr.podcast.server.http.representation
import fr.podcast.server.http.respondProblem
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respondText
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
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

        get("/subscriptions/opml") {
            val podcasts = catalog(call).subscriptions()
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "podcasts.opml").toString(),
            )
            call.respondText(Opml.write("${call.user.displayName} — ${services.config.name}", podcasts), OpmlContentType)
        }

        // Instant: new podcasts wait for their feed to be read (refreshStatus PENDING).
        post("/subscriptions/opml") {
            call.mutatingUser() ?: return@post
            val entries = try {
                val bytes = call.receiveUpload(MAX_OPML_BYTES) ?: throw OpmlException("OPML file expected")
                Opml.read(bytes.inputStream())
            } catch (unreadable: OpmlException) {
                return@post call.respondProblem(HttpStatusCode.UnprocessableEntity, unreadable.message, type = ProblemTypes.NOT_AN_OPML)
            }
            val import = catalog(call).importFeeds(entries, services.guard)
            services.refreshSoon(import.newPodcasts)
            if (call.isHtmx) call.response.header("HX-Trigger", "subscriptions-changed")
            call.respondText(opmlImportResource(import.report).toString(), HalJson)
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

internal suspend fun ApplicationCall.respondFeedProblem(refused: FeedException) =
    respondProblem(HttpStatusCode.UnprocessableEntity, refused.message, listOf(FieldError("feedUrl", refused.code)), "/problems/${refused.code}")

private const val MAX_OPML_BYTES = 5L * 1024 * 1024

private val OpmlContentType = ContentType("text", "x-opml")

/** The request body, or the first file of a multipart form (thin client upload); null if too large. */
private suspend fun ApplicationCall.receiveUpload(limit: Long): ByteArray? {
    if (request.contentType().match(ContentType.MultiPart.FormData)) {
        var file: ByteArray? = null
        receiveMultipart(formFieldLimit = limit).forEachPart { part ->
            if (part is PartData.FileItem && file == null) file = part.provider().readRemaining(limit + 1).readByteArray()
            part.dispose()
        }
        return file?.takeIf { it.size <= limit }
    }
    return receiveChannel().readRemaining(limit + 1).readByteArray().takeIf { it.size <= limit }
}
