package fr.podcast.server.api

import fr.podcast.server.auth.SessionPrincipal
import fr.podcast.server.auth.UserAccount
import fr.podcast.server.auth.authPrincipal
import fr.podcast.server.html.noticeFragment
import fr.podcast.server.http.HalJson
import fr.podcast.server.http.Representation
import fr.podcast.server.http.isHtmx
import fr.podcast.server.http.respondProblem
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

/* Helpers shared by the API routes. */

internal const val DEFAULT_LIMIT = 20
internal const val MAX_LIMIT = 100

internal val ApplicationCall.user: UserAccount get() = authPrincipal!!.user

internal val Representation.forms: Boolean get() = this == Representation.HalForms

/** JSON resources; a browser asking for HTML gets HAL when the thin client has no page for it. */
internal suspend fun ApplicationCall.respondResource(resource: JsonObject, representation: Representation, status: HttpStatusCode = HttpStatusCode.OK) {
    val contentType = if (representation == Representation.Html) HalJson else representation.contentType
    respondText(resource.toString(), contentType, status)
}

internal suspend fun ApplicationCall.respondNotFound(what: String) =
    respondProblem(HttpStatusCode.NotFound, "$what ${parameters["id"]} not found")

/** For htmx: a sentence telling what was done, and [trigger] so the page reloads what changed. */
internal suspend fun ApplicationCall.respondNotice(message: String, trigger: String? = null) {
    trigger?.let { response.header("HX-Trigger", it) }
    respondFragment(noticeFragment(message))
}

/** 204; htmx also gets [trigger] so the page reloads what changed. */
internal suspend fun ApplicationCall.respondChanged(trigger: String = "episodes-changed") {
    if (isHtmx) response.header("HX-Trigger", trigger)
    respond(HttpStatusCode.NoContent)
}

/**
 * The user, for a request that changes state. Session requests must come from htmx:
 * a cross-site form cannot set the HX-Request header (CSRF, ADR 0005).
 */
internal suspend fun ApplicationCall.mutatingUser(): UserAccount? {
    val principal = authPrincipal!!
    if (principal is SessionPrincipal && !isHtmx) {
        respondProblem(HttpStatusCode.Forbidden, "Requests authenticated by session must be sent by htmx (HX-Request)")
        return null
    }
    return principal.user
}

internal data class Paging(val offset: Int, val limit: Int)

internal suspend fun ApplicationCall.paging(): Paging? {
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

internal suspend fun ApplicationCall.receiveJsonObject(): JsonObject? =
    runCatching { Json.parseToJsonElement(receiveText()).jsonObject }.getOrNull()

/**
 * The request fields, from JSON (SDK, CLI) or from a form (thin client, htmx).
 * Form values are strings; [booleanField] and [stringField] read both kinds.
 */
internal suspend fun ApplicationCall.receiveFields(): Map<String, JsonElement>? =
    if (request.contentType().match(ContentType.Application.FormUrlEncoded)) {
        receiveParameters().entries().associate { (name, values) -> name to JsonPrimitive(values.lastOrNull().orEmpty()) }
    } else {
        receiveJsonObject()
    }

internal fun Map<String, JsonElement>.stringField(name: String): String? =
    (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun Map<String, JsonElement>.booleanField(name: String): Boolean? {
    val value = get(name) as? JsonPrimitive ?: return null
    return value.booleanOrNull ?: when (value.content.lowercase()) {
        "true", "on", "1" -> true
        "false", "off", "0" -> false
        else -> null
    }
}

/** True when the field holds something (not absent, not null, not an empty form value). */
internal fun Map<String, JsonElement>.hasField(name: String): Boolean {
    val value = get(name) as? JsonPrimitive ?: return false
    return value !is JsonNull && value.content.isNotBlank()
}

/** An integer from JSON (number) or from a form (digits); null when absent or not an integer. */
internal fun Map<String, JsonElement>.intField(name: String): Int? {
    val value = get(name) as? JsonPrimitive ?: return null
    if (value is JsonNull) return null
    return value.content.trim().toIntOrNull()
}

internal fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
