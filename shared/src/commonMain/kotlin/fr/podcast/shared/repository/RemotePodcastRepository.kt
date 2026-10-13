package fr.podcast.shared.repository

import fr.podcast.shared.account.Account
import fr.podcast.shared.api.ApiException
import fr.podcast.shared.api.HalResource
import fr.podcast.shared.api.PodcastApi
import fr.podcast.shared.api.expandUriTemplate
import fr.podcast.shared.model.Episode
import fr.podcast.shared.model.Podcast
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * [PodcastRepository] backed by a server, for one [Account]. It starts from the root
 * resource and follows its links; actions go through the HAL-FORMS templates, so they
 * are only attempted when the server offers them (ADR 0003).
 */
class RemotePodcastRepository(account: Account, httpClient: HttpClient) : PodcastRepository {

    private val api = PodcastApi(account.serverUrl, httpClient, account.token)

    // The root's links do not change during a session; its embedded Home does.
    private var root: HalResource? = null

    private suspend fun root(refresh: Boolean = false): HalResource =
        (if (refresh) null else root) ?: api.get("/").also { root = it }

    private suspend fun link(rel: String, values: Map<String, Any?> = emptyMap()): String {
        val href = root().link(rel) ?: throw ApiException.Unexpected(200, "${api.serverUrl}/ (no \"$rel\" link)")
        return expandUriTemplate(href, values)
    }

    override suspend fun subscriptions(): List<Podcast> =
        api.get(link("subscriptions")).embedded("subscriptions").flatMap { it.embedded("podcast") }.map { it.decode(Podcast.serializer()) }

    override suspend fun podcast(id: String): Podcast? = orNullIfNotFound {
        api.get(link("podcast", mapOf("id" to id))).decode(Podcast.serializer())
    }

    override suspend fun episode(id: String): Episode? = episodeResource(id)?.decode(Episode.serializer())

    override suspend fun latestEpisodes(limit: Int): List<Episode> =
        api.get(link("episodes", mapOf("limit" to limit.coerceIn(1, MAX_PAGE)))).embedded("episodes").map { it.decode(Episode.serializer()) }

    override suspend fun inProgress(): List<Episode> = root(refresh = true).embedded("resume").map { it.decode(Episode.serializer()) }

    override suspend fun updateProgress(episodeId: String, positionSeconds: Int) =
        perform(episodeId, "update-progress", mapOf("positionSeconds" to JsonPrimitive(positionSeconds)))

    override suspend fun markPlayed(episodeId: String) = perform(episodeId, "mark-played")

    override suspend fun markUnplayed(episodeId: String) = perform(episodeId, "mark-unplayed")

    /** Runs the episode's [template] if the server currently offers it. */
    private suspend fun perform(episodeId: String, template: String, values: Map<String, JsonElement> = emptyMap()) {
        val episode = episodeResource(episodeId) ?: return
        val action = episode.template(template) ?: return
        api.submit(action, episode.link("self") ?: link("episode", mapOf("id" to episodeId)), values)
    }

    private suspend fun episodeResource(id: String): HalResource? = orNullIfNotFound { api.get(link("episode", mapOf("id" to id))) }

    private inline fun <T> orNullIfNotFound(block: () -> T): T? =
        try {
            block()
        } catch (notFound: ApiException.NotFound) {
            null
        }

    private companion object {
        const val MAX_PAGE = 100
    }
}
