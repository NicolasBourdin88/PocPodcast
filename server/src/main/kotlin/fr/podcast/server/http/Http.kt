package fr.podcast.server.http

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.acceptItems
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

val HalJson = ContentType("application", "hal+json")
val HalFormsJson = ContentType("application", "prs.hal-forms+json")
val ProblemJson = ContentType("application", "problem+json")

/** The three representations of every resource (docs/api/conventions.md). */
enum class Representation(val contentType: ContentType) {
    Html(ContentType.Text.Html),
    Hal(HalJson),
    HalForms(HalFormsJson),
}

/** Picks the representation from Accept, in the client's order of preference. JSON when unspecified. */
fun ApplicationCall.representation(): Representation {
    for (item in request.acceptItems()) {
        val accepted = runCatching { ContentType.parse(item.value).withoutParameters() }.getOrNull() ?: continue
        when {
            accepted == HalFormsJson -> return Representation.HalForms
            accepted == ContentType.Text.Html || accepted == ContentType("application", "xhtml+xml") -> return Representation.Html
            accepted == HalJson || accepted == ContentType.Application.Json -> return Representation.Hal
            accepted.contentSubtype == "*" -> return Representation.Hal
        }
    }
    return Representation.Hal
}

/** True for requests sent by htmx (thin client). */
val ApplicationCall.isHtmx: Boolean get() = request.header("HX-Request") == "true"

data class FieldError(val field: String, val message: String)

/** Problem Details (RFC 9457). */
suspend fun ApplicationCall.respondProblem(status: HttpStatusCode, detail: String? = null, errors: List<FieldError> = emptyList()) {
    val problem = buildJsonObject {
        put("type", "about:blank")
        put("title", status.description)
        put("status", status.value)
        detail?.let { put("detail", it) }
        if (errors.isNotEmpty()) {
            putJsonArray("errors") {
                errors.forEach { addJsonObject { put("field", it.field); put("message", it.message) } }
            }
        }
    }
    respondText(problem.toString(), ProblemJson, status)
}

/** Browsers go to the login page, API clients get a 401 Problem. */
suspend fun ApplicationCall.respondUnauthenticated() {
    when {
        isHtmx -> {
            response.header("HX-Redirect", "/login")
            respond(HttpStatusCode.Unauthorized)
        }
        representation() == Representation.Html -> respondSeeOther("/login")
        else -> {
            response.header(HttpHeaders.WWWAuthenticate, "Bearer realm=\"podcast\"")
            respondProblem(HttpStatusCode.Unauthorized, "Authentication required")
        }
    }
}

suspend fun ApplicationCall.respondSeeOther(location: String) {
    response.header(HttpHeaders.Location, location)
    respond(HttpStatusCode.SeeOther)
}
