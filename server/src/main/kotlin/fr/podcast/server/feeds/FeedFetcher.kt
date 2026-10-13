package fr.podcast.server.feeds

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FilterInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.UnknownHostException

/** Why a feed could not be read; each maps to a Problem type of the API. */
sealed class FeedException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class InvalidUrl(message: String) : FeedException(message)
    class ForbiddenAddress(host: String) : FeedException("Address of $host is not allowed")
    class Unreachable(message: String, cause: Throwable? = null) : FeedException(message, cause)
    class NotAFeed(message: String) : FeedException(message)
    class TooLarge(limit: Long) : FeedException("Larger than $limit bytes")
}

/** Short reason, also the last segment of the API Problem type (`/problems/<code>`). */
val FeedException.code: String
    get() = when (this) {
        is FeedException.InvalidUrl -> "invalid-feed-url"
        is FeedException.ForbiddenAddress -> "forbidden-feed-address"
        is FeedException.Unreachable -> "feed-unreachable"
        is FeedException.NotAFeed -> "not-a-feed"
        is FeedException.TooLarge -> "feed-too-large"
    }

/** Users often paste "example.org/feed": HTTPS is assumed when the scheme is missing. */
fun withScheme(url: String): String = url.trim().let { if ("://" in it) it else "https://$it" }

/**
 * The server fetches URLs given by users: without this check, anyone could make it call
 * internal services (SSRF), e.g. the database or a cloud metadata endpoint. Only http(s)
 * to public addresses, checked again on every redirect.
 *
 * Limit: a DNS answer can change between this check and the connection (DNS rebinding).
 */
class UrlGuard(
    private val allowPrivateNetworks: Boolean,
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
) {
    fun check(raw: String): Url {
        // Ktor parses anything ("feed.xml" becomes http://localhost/feed.xml): require an explicit scheme first.
        val trimmed = raw.trim()
        val explicitScheme = trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)
        val url = if (explicitScheme) runCatching { Url(trimmed) }.getOrNull() else null
        if (url == null || url.protocol.name !in setOf("http", "https") || url.host.isBlank()) {
            throw FeedException.InvalidUrl("Not an http(s) URL: $raw")
        }
        if (allowPrivateNetworks) return url
        val addresses = try {
            resolve(url.host)
        } catch (unknown: UnknownHostException) {
            throw FeedException.Unreachable("Unknown host ${url.host}", unknown)
        }
        if (addresses.isEmpty() || addresses.any(::isPrivate)) throw FeedException.ForbiddenAddress(url.host)
        return url
    }

    private fun isPrivate(address: InetAddress): Boolean {
        val bytes = address.address
        val first = bytes[0].toInt() and 0xff
        val second = bytes.getOrNull(1)?.toInt()?.and(0xff) ?: 0
        return address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress ||
            address.isAnyLocalAddress || address.isMulticastAddress ||
            (bytes.size == 4 && first == 0) || // 0.0.0.0/8
            (bytes.size == 4 && first == 100 && second in 64..127) || // carrier-grade NAT 100.64.0.0/10
            (bytes.size == 16 && (first and 0xfe) == 0xfc) // IPv6 unique local fc00::/7
    }
}

sealed interface FetchResult {
    data object NotModified : FetchResult

    data class Fetched(
        val feed: ParsedFeed,
        val etag: String?,
        val lastModified: String?,
        /** Set when the feed answered with a permanent redirect: the podcast moved. */
        val movedTo: String?,
    ) : FetchResult
}

/** Downloads and parses feeds with conditional requests (ETag, Last-Modified). */
class FeedFetcher(
    client: HttpClient,
    guard: UrlGuard,
    private val maxBytes: Long = 20L * 1024 * 1024,
) {
    private val http = SafeHttp(client, guard)

    suspend fun fetch(url: String, etag: String? = null, lastModified: String? = null): FetchResult =
        http.get(
            url,
            configure = {
                header(HttpHeaders.Accept, "application/rss+xml, application/xml;q=0.9, text/xml;q=0.9, */*;q=0.5")
                header(HttpHeaders.UserAgent, SafeHttp.USER_AGENT)
                etag?.let { header(HttpHeaders.IfNoneMatch, it) }
                lastModified?.let { header(HttpHeaders.IfModifiedSince, it) }
            },
        ) { response, finalUrl, redirects ->
            when {
                response.status == HttpStatusCode.NotModified -> FetchResult.NotModified
                !response.status.isSuccess() -> throw FeedException.Unreachable("HTTP ${response.status.value} for $finalUrl")
                else -> {
                    // Streamed: the size limit applies while reading, not after a full download.
                    val feed = withContext(Dispatchers.IO) {
                        LimitedInputStream(response.bodyAsChannel().toInputStream(), maxBytes).use { RssParser.parse(it) }
                    }
                    FetchResult.Fetched(
                        feed = feed,
                        etag = response.headers[HttpHeaders.ETag],
                        lastModified = response.headers[HttpHeaders.LastModified],
                        movedTo = finalUrl.toString().takeIf { redirects.followed && redirects.allPermanent && it != url },
                    )
                }
            }
        }
}

/** Stops reading past [limit] bytes, so a huge or endless response cannot exhaust memory. */
internal class LimitedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int = super.read().also { if (it >= 0) count(1) }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        super.read(buffer, offset, length).also { if (it > 0) count(it.toLong()) }

    private fun count(bytes: Long) {
        count += bytes
        if (count > limit) throw FeedException.TooLarge(limit)
    }
}
