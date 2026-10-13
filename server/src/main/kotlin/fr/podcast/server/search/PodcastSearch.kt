package fr.podcast.server.search

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import kotlin.time.Duration.Companion.seconds

/** A podcast found by a search source; [feedUrl] is what the user subscribes to. */
data class SearchHit(
    val title: String,
    val author: String,
    val feedUrl: String,
    val artworkUrl: String?,
    val description: String,
    val source: String,
)

/** A podcast directory the server can query on behalf of its users. */
interface SearchSource {
    /** Short identifier shown in results ("apple", "podcastindex"). */
    val name: String
    suspend fun search(query: String, limit: Int): List<SearchHit>
}

class SearchFailedException(message: String) : Exception(message)

data class SearchOutcome(val hits: List<SearchHit>, val answeredBy: List<String>)

/**
 * Asks every source in parallel and merges their answers, interleaved so that no directory
 * dominates, without duplicates (same feed). A source that fails or is slow is skipped.
 */
class PodcastSearch(private val sources: List<SearchSource>) {
    private val log = LoggerFactory.getLogger(PodcastSearch::class.java)

    suspend fun search(query: String, limit: Int = 20): SearchOutcome = coroutineScope {
        val answers = sources.map { source ->
            async {
                try {
                    withTimeoutOrNull(8.seconds) { source.name to source.search(query, limit) }
                        ?: null.also { log.info("{} too slow", source.name) }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    log.info("{} failed: {}", source.name, failure.message)
                    null
                }
            }
        }.awaitAll().filterNotNull()
        if (answers.isEmpty()) throw SearchFailedException("No search source answered")

        val merged = LinkedHashMap<String, SearchHit>()
        val longest = answers.maxOf { it.second.size }
        for (rank in 0 until longest) {
            for ((_, hits) in answers) {
                val hit = hits.getOrNull(rank) ?: continue
                merged.putIfAbsent(normalizedFeed(hit.feedUrl), hit)
            }
        }
        SearchOutcome(merged.values.take(limit), answers.map { it.first })
    }
}

/** "https://Example.org/feed/" and "http://example.org/feed" are the same feed. */
fun normalizedFeed(url: String): String =
    url.trim().substringAfter("://").lowercase().trimEnd('/')

private val lenientJson = Json { ignoreUnknownKeys = true }

private fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.ifEmpty { null }

/** Apple Podcasts directory (iTunes Search API): no key needed. */
class AppleSource(private val client: HttpClient) : SearchSource {
    override val name = "apple"

    override suspend fun search(query: String, limit: Int): List<SearchHit> {
        val response = client.get("https://itunes.apple.com/search") {
            parameter("media", "podcast")
            parameter("entity", "podcast")
            parameter("limit", limit)
            parameter("term", query)
            header(HttpHeaders.UserAgent, USER_AGENT)
        }
        if (!response.status.isSuccess()) throw IllegalStateException("Apple answered ${response.status}")
        val results = lenientJson.parseToJsonElement(response.bodyAsText()).jsonObject["results"] as? JsonArray ?: return emptyList()
        return results.mapNotNull { element ->
            val result = element.jsonObject
            val feed = result.text("feedUrl") ?: return@mapNotNull null // some entries have no public feed
            SearchHit(
                title = result.text("collectionName") ?: return@mapNotNull null,
                author = result.text("artistName").orEmpty(),
                feedUrl = feed,
                artworkUrl = result.text("artworkUrl600") ?: result.text("artworkUrl100"),
                description = "",
                source = name,
            )
        }
    }
}

/**
 * Podcast Index (https://podcastindex.org), open directory of the Podcast 2.0 movement.
 * Each request is signed: Authorization = SHA-1(key + secret + unix time).
 */
class PodcastIndexSource(
    private val client: HttpClient,
    private val key: String,
    private val secret: String,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) : SearchSource {
    override val name = "podcastindex"

    override suspend fun search(query: String, limit: Int): List<SearchHit> {
        val date = now().toString()
        val response = client.get("https://api.podcastindex.org/api/1.0/search/byterm") {
            parameter("q", query)
            parameter("max", limit)
            header(HttpHeaders.UserAgent, USER_AGENT)
            header("X-Auth-Date", date)
            header("X-Auth-Key", key)
            header(HttpHeaders.Authorization, sha1Hex(key + secret + date))
        }
        if (!response.status.isSuccess()) throw IllegalStateException("Podcast Index answered ${response.status}")
        val feeds = lenientJson.parseToJsonElement(response.bodyAsText()).jsonObject["feeds"] as? JsonArray ?: return emptyList()
        return feeds.mapNotNull { element ->
            val feed = element.jsonObject
            SearchHit(
                title = feed.text("title") ?: return@mapNotNull null,
                author = feed.text("author") ?: feed.text("ownerName").orEmpty(),
                feedUrl = feed.text("url") ?: return@mapNotNull null,
                artworkUrl = feed.text("artwork") ?: feed.text("image"),
                description = feed.text("description").orEmpty().take(500),
                source = name,
            )
        }
    }

    companion object {
        fun sha1Hex(value: String): String =
            MessageDigest.getInstance("SHA-1").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

private const val USER_AGENT = "Podcast/0.4 (master project; Ktor)"
