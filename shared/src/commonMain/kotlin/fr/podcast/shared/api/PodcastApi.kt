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
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Failures a client can explain to the user. */
sealed class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Unreachable(cause: Throwable) : ApiException("Server unreachable: ${cause.message}", cause)
    class NotAPodcastServer(url: String) : ApiException("$url is not a podcast server")
    class Unauthorized : ApiException("Authentication refused")
    class NotFound(url: String) : ApiException("$url not found")
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
            !response.status.isSuccess() -> throw ApiException.Unexpected(response.status.value, url)
        }
        return response
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
