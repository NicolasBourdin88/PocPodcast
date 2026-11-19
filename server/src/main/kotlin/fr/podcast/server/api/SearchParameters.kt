package fr.podcast.server.api

import fr.podcast.server.http.respondProblem
import fr.podcast.shared.model.EpisodeQuery
import fr.podcast.shared.model.EpisodeStatus
import fr.podcast.shared.model.TagRules
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDate

/** The filters of GET /episodes (ADR 0012). */
internal val EPISODE_FILTERS = listOf("q", "status", "favorite", "minRating", "from", "to", "podcast", "tag")

private const val MAX_QUERY_LENGTH = 200

/**
 * The filters of GET /episodes, and those given (not blank: an empty form field means "any"), for
 * the links of the other pages. Null after answering 400.
 */
internal suspend fun ApplicationCall.episodeQuery(): Pair<EpisodeQuery, Map<String, String>>? {
    val given = EPISODE_FILTERS.mapNotNull { name ->
        request.queryParameters.getAll(name)?.lastOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { name to it }
    }.toMap()
    suspend fun refuse(message: String): Pair<EpisodeQuery, Map<String, String>>? {
        respondProblem(HttpStatusCode.BadRequest, message)
        return null
    }
    val status = given["status"]?.let { value ->
        EpisodeStatus.entries.firstOrNull { it.name == value } ?: return refuse("status must be one of ${EpisodeStatus.entries.joinToString()}")
    }
    val favorite = given["favorite"]?.let { it.toBooleanStrictOrNull() ?: return refuse("favorite is true or false") } ?: false
    val minRating = given["minRating"]?.let { it.toIntOrNull()?.takeIf { rating -> rating in 0..5 } ?: return refuse("minRating is between 0 and 5") } ?: 0
    val from = given["from"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: return refuse("from is a date, YYYY-MM-DD") }
    val to = given["to"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: return refuse("to is a date, YYYY-MM-DD") }
    val podcast = given["podcast"]?.let { it.toLongOrNull()?.toString() ?: return refuse("podcast is a podcast id") }
    val tag = given["tag"]?.let { it.takeIf { tag -> tag.length <= TagRules.MAX_LENGTH } ?: return refuse("tag has at most ${TagRules.MAX_LENGTH} characters") }
    val text = given["q"]?.let { it.takeIf { q -> q.length <= MAX_QUERY_LENGTH } ?: return refuse("q has at most $MAX_QUERY_LENGTH characters") }
    return EpisodeQuery(text.orEmpty(), status, favorite, minRating, from, to, podcast, tag) to given
}

