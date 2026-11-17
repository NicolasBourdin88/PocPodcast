package fr.podcast.shared.api

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** A field the server refused, and why (Problem Details `errors`, docs/api/conventions.md). */
data class FieldError(val field: String, val message: String)

/** Failures a client can explain to the user. */
sealed class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Unreachable(cause: Throwable) : ApiException("Server unreachable: ${cause.message}", cause)
    class NotAPodcastServer(url: String) : ApiException("$url is not a podcast server")
    class Unauthorized : ApiException("Authentication refused")
    class NotFound(url: String) : ApiException("$url not found")

    /** The server refused the request and said why (Problem Details, RFC 9457), field by field when it can. */
    class Rejected(val status: Int, val type: String?, detail: String?, val errors: List<FieldError> = emptyList()) :
        ApiException(detail ?: "Refused ($status, $type)")

    /** Too many wrong passwords: the server takes a new attempt in [retryAfterSeconds] (ADR 0009). */
    class TooManyAttempts(val retryAfterSeconds: Long) : ApiException("Too many attempts: retry in $retryAfterSeconds s")

    /** The resource does not offer this action (no such HAL-FORMS template): the user may not do it. */
    class NotOffered(action: String) : ApiException("\"$action\" is not offered")

    class Unexpected(status: Int, url: String) : ApiException("Unexpected status $status for $url")
}

/** Low-level access to one server: reads HAL documents and submits HAL-FORMS templates. */
class PodcastApi(val serverUrl: String, private val httpClient: HttpClient, private val token: String? = null) {

    /** Hrefs are relative to the server root; absolute ones (RSS feed…) are kept. */
    fun resolve(href: String): String =
        if (href.startsWith("http://") || href.startsWith("https://")) href else serverUrl.trimEnd('/') + href

    suspend fun get(href: String): HalResource {
        val url = resolve(href)
        val response = send(url) {
            method = HttpMethod.Get
            accept(HalFormsJson)
        }
        return response.halResource(url)
    }

    /**
     * Submits [template] with [values], completed with the template's default values.
     * [self] is the target when the template has none.
     */
    suspend fun submit(template: HalFormsTemplate, self: String, values: Map<String, JsonElement> = emptyMap()): HttpResponse {
        val body = template.properties.associate { property ->
            val value = values[property.name] ?: property.value
            require(value != null || !property.required) { "${template.name}: ${property.name} is required" }
            property.name to (value ?: JsonNull)
        }
        val url = resolve(template.target ?: self)
        return send(url) {
            method = template.method
            accept(HalFormsJson)
            if (template.properties.isNotEmpty()) {
                contentType(ContentType.Application.Json)
                setBody(JsonObject(body).toString())
            }
        }
    }

    /** A non-HAL representation (e.g. OPML). */
    suspend fun getText(href: String, accept: ContentType): String {
        val url = resolve(href)
        return send(url) {
            method = HttpMethod.Get
            accept(accept)
        }.bodyAsText()
    }

    /** Posts a raw body (e.g. an OPML file) and reads the HAL answer. */
    suspend fun postText(href: String, body: String, contentType: ContentType): HalResource {
        val url = resolve(href)
        return send(url) {
            method = HttpMethod.Post
            accept(HalFormsJson)
            contentType(contentType)
            setBody(body)
        }.halResource(url)
    }

    private suspend fun send(url: String, block: HttpRequestBuilder.() -> Unit): HttpResponse {
        val response = try {
            httpClient.request {
                url(url)
                token?.let { bearerAuth(it) }
                block()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            throw ApiException.Unreachable(failure)
        }
        when {
            response.status == HttpStatusCode.Unauthorized -> throw ApiException.Unauthorized()
            response.status == HttpStatusCode.NotFound -> throw ApiException.NotFound(url)
            response.status == HttpStatusCode.TooManyRequests ->
                throw ApiException.TooManyAttempts(response.headers[HttpHeaders.RetryAfter]?.toLongOrNull() ?: DEFAULT_RETRY_AFTER_SECONDS)
            !response.status.isSuccess() -> throw response.problem() ?: ApiException.Unexpected(response.status.value, url)
        }
        return response
    }

    private companion object {
        const val DEFAULT_RETRY_AFTER_SECONDS = 60L
    }
}

internal suspend fun HttpResponse.halResource(url: String): HalResource =
    try {
        HalResource(apiJson.parseToJsonElement(bodyAsText()).jsonObject)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (notJson: Exception) {
        throw ApiException.NotAPodcastServer(url)
    }

/** The Problem Details of a refusal, if the server sent some. */
private suspend fun HttpResponse.problem(): ApiException.Rejected? {
    if (contentType()?.match(ContentType("application", "problem+json")) != true) return null
    val problem = runCatching { apiJson.parseToJsonElement(bodyAsText()).jsonObject }.getOrNull() ?: return null
    fun JsonObject.text(name: String) = (get(name) as? JsonPrimitive)?.content
    val errors = (problem["errors"] as? JsonArray).orEmpty().mapNotNull { error ->
        val fields = error as? JsonObject ?: return@mapNotNull null
        FieldError(fields.text("field") ?: return@mapNotNull null, fields.text("message").orEmpty())
    }
    return ApiException.Rejected(status.value, problem.text("type"), problem.text("detail"), errors)
}
