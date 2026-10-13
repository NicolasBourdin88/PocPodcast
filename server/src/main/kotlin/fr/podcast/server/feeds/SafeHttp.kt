package fr.podcast.server.feeds

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.takeFrom
import kotlinx.coroutines.CancellationException

/** How the final response was reached. */
data class Redirects(val followed: Boolean, val allPermanent: Boolean)

/**
 * GET requests to addresses given by users (feeds, audio files):
 * - redirects are followed by hand, each hop checked by [guard] (SSRF);
 * - the final response is handed over as a stream (`prepareGet().execute`), never loaded in
 *   memory first, so size limits really apply and large audio files can be written to disk.
 */
class SafeHttp(private val client: HttpClient, private val guard: UrlGuard) {

    suspend fun <T> get(
        url: String,
        configure: HttpRequestBuilder.() -> Unit = {},
        onResponse: suspend (response: HttpResponse, finalUrl: Url, redirects: Redirects) -> T,
    ): T {
        var current = guard.check(url)
        var followed = false
        var allPermanent = true
        repeat(MAX_REDIRECTS + 1) {
            val step = try {
                client.prepareGet(current) { configure() }.execute { response ->
                    val location = response.headers[HttpHeaders.Location]
                    if (response.status.value in 300..399 && response.status != HttpStatusCode.NotModified && location != null) {
                        followed = true
                        allPermanent = allPermanent && response.status in PERMANENT_REDIRECTS
                        Step.Redirect(guard.check(URLBuilder(current).takeFrom(location).buildString()))
                    } else {
                        Step.Done(onResponse(response, current, Redirects(followed, allPermanent)))
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (refused: FeedException) {
                throw refused
            } catch (failure: Exception) {
                throw FeedException.Unreachable("Cannot reach ${current.host}: ${failure.message}", failure)
            }
            when (step) {
                is Step.Redirect -> current = step.url
                is Step.Done -> return step.value
            }
        }
        throw FeedException.Unreachable("Too many redirects from $url")
    }

    private sealed interface Step<out T> {
        data class Redirect(val url: Url) : Step<Nothing>
        data class Done<T>(val value: T) : Step<T>
    }

    companion object {
        const val MAX_REDIRECTS = 5
        const val USER_AGENT = "Podcast/0.4 (master project; Ktor)"
        private val PERMANENT_REDIRECTS = setOf(HttpStatusCode.MovedPermanently, HttpStatusCode.PermanentRedirect)
    }
}
