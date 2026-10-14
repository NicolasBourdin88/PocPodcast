package fr.podcast.shared.repository

import fr.podcast.shared.account.Account
import fr.podcast.shared.api.ApiException
import fr.podcast.shared.api.HalResource
import fr.podcast.shared.api.PodcastApi
import fr.podcast.shared.api.expandUriTemplate
import fr.podcast.shared.api.halResource
import fr.podcast.shared.home.EpisodeItem
import fr.podcast.shared.model.Episode
import fr.podcast.shared.model.OpmlImportReport
import fr.podcast.shared.model.Podcast
import fr.podcast.shared.model.SearchResult
import fr.podcast.shared.model.Subscription
import fr.podcast.shared.model.SubscriptionItem
import fr.podcast.shared.model.Transcript
import io.ktor.client.HttpClient
import io.ktor.http.ContentType
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * [PodcastClient] backed by a server, for one [Account]. It starts from the root resource and
 * follows its links; actions go through the HAL-FORMS templates, so they are only attempted
 * when the server offers them (ADR 0003).
 */
class RemotePodcastRepository(account: Account, httpClient: HttpClient) : PodcastClient {

    private val api = PodcastApi(account.serverUrl, httpClient, account.token)

    // The root's links do not change during a session; its embedded Home does.
    private var root: HalResource? = null

    private suspend fun root(refresh: Boolean = false): HalResource =
        (if (refresh) null else root) ?: api.get("/").also { root = it }

    private suspend fun link(rel: String, values: Map<String, Any?> = emptyMap()): String {
        val href = root().link(rel) ?: throw ApiException.Unexpected(200, "${api.serverUrl}/ (no \"$rel\" link)")
        return expandUriTemplate(href, values)
    }

    // --- PodcastRepository ---

    override suspend fun subscriptions(): List<Podcast> = subscriptionItems().map { it.podcast }

    override suspend fun podcast(id: String): Podcast? = orNullIfNotFound { api.get(link("podcast", mapOf("id" to id))).toPodcast() }

    override suspend fun episode(id: String): Episode? = episodeResource(id)?.asEpisode()

    override suspend fun latestEpisodes(limit: Int): List<Episode> =
        api.get(link("episodes", mapOf("limit" to limit.coerceIn(1, MAX_PAGE)))).embedded("episodes").map { it.asEpisode() }

    override suspend fun inProgress(): List<Episode> = root(refresh = true).embedded("resume").map { it.asEpisode() }

    override suspend fun updateProgress(episodeId: String, positionSeconds: Int) =
        perform(episodeId, "update-progress", mapOf("positionSeconds" to JsonPrimitive(positionSeconds)))

    override suspend fun markPlayed(episodeId: String) = perform(episodeId, "mark-played")

    override suspend fun markUnplayed(episodeId: String) = perform(episodeId, "mark-unplayed")

    // --- LibraryRepository ---

    override suspend fun subscriptionItems(): List<SubscriptionItem> =
        subscriptionCollection().embedded("subscriptions").map { it.toSubscriptionItem() }

    override suspend fun subscribe(feedUrl: String): SubscriptionItem {
        val collection = subscriptionCollection()
        val subscribe = collection.template("subscribe") ?: throw ApiException.Unexpected(200, "no subscribe template")
        return api.submit(subscribe, link("subscriptions"), mapOf("feedUrl" to JsonPrimitive(feedUrl)))
            .halResource(api.serverUrl).toSubscriptionItem()
    }

    override suspend fun updateSubscription(subscriptionId: String, note: String?, autoDownload: Boolean): SubscriptionItem {
        val subscription = subscriptionResource(subscriptionId)
        val update = subscription.template("update") ?: throw ApiException.Unexpected(200, "no update template")
        val values = mapOf("note" to (note?.let(::JsonPrimitive) ?: JsonNull), "autoDownload" to JsonPrimitive(autoDownload))
        return api.submit(update, subscription.link("self")!!, values).halResource(api.serverUrl).toSubscriptionItem()
    }

    override suspend fun unsubscribe(subscriptionId: String) {
        val subscription = subscriptionResource(subscriptionId)
        subscription.template("unsubscribe")?.let { api.submit(it, subscription.link("self")!!) }
    }

    override suspend fun refreshPodcast(podcastId: String): Podcast? {
        val podcast = orNullIfNotFound { api.get(link("podcast", mapOf("id" to podcastId))) } ?: return null
        val refresh = podcast.template("refresh") ?: return podcast.toPodcast()
        return api.submit(refresh, podcast.link("self")!!).halResource(api.serverUrl).toPodcast()
    }

    override suspend fun podcastEpisodes(podcastId: String, limit: Int): List<Episode> {
        val podcast = orNullIfNotFound { api.get(link("podcast", mapOf("id" to podcastId))) } ?: return emptyList()
        val href = expandUriTemplate(podcast.link("episodes") ?: return emptyList(), mapOf("limit" to limit.coerceIn(1, MAX_PAGE)))
        return api.get(href).embedded("episodes").map { it.asEpisode() }
    }

    override suspend fun search(query: String): List<SearchResult>? {
        val template = root().link("search") ?: return null
        return api.get(expandUriTemplate(template, mapOf("q" to query))).embedded("results").map { it.decode(SearchResult.serializer()) }
    }

    override suspend fun keep(episodeId: String) = perform(episodeId, "keep")

    override suspend fun unkeep(episodeId: String) = perform(episodeId, "unkeep")

    override suspend fun download(episodeId: String) = perform(episodeId, "download")

    // --- PlaybackRepository ---

    override suspend fun episodeItem(id: String): EpisodeItem? = episodeResource(id)?.asEpisodeItem()

    override suspend fun queue(): List<EpisodeItem> = queueResource().embedded("episodes").map { it.asEpisodeItem() }

    override suspend fun enqueue(episodeId: String, position: Int?) =
        perform(episodeId, "enqueue", position?.let { mapOf("position" to JsonPrimitive(it)) } ?: emptyMap())

    override suspend fun dequeue(episodeId: String) = perform(episodeId, "dequeue")

    override suspend fun moveInQueue(episodeId: String, position: Int) =
        perform(episodeId, "move", mapOf("position" to JsonPrimitive(position)))

    override suspend fun clearQueue() {
        val queue = queueResource()
        queue.template("clear-queue")?.let { api.submit(it, queue.link("self") ?: link("queue")) }
    }

    override suspend fun exportOpml(): String =
        api.getText(subscriptionCollection().link("opml") ?: throw ApiException.Unexpected(200, "no opml link"), OPML)

    override suspend fun importOpml(opml: String): OpmlImportReport {
        val href = subscriptionCollection().link("opml") ?: throw ApiException.Unexpected(200, "no opml link")
        return api.postText(href, opml, OPML).decode(OpmlImportReport.serializer())
    }

    // --- Helpers ---

    private suspend fun subscriptionCollection(): HalResource = api.get(link("subscriptions"))

    private suspend fun queueResource(): HalResource = api.get(link("queue"))

    /** An episode whose audio link is absolute, ready for a player. */
    private fun HalResource.asEpisode(): Episode = toEpisode().copy(audioUrl = link("audio")?.let(api::resolve))

    private fun HalResource.asEpisodeItem(): EpisodeItem = EpisodeItem(asEpisode(), embedded("podcast").single().toPodcast())

    private suspend fun subscriptionResource(id: String): HalResource =
        subscriptionCollection().embedded("subscriptions").firstOrNull { it.string("id") == id }
            ?: throw ApiException.NotFound("subscription $id")

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
        val OPML = ContentType("text", "x-opml")
    }
}

/** An episode with the Podcast 2.0 resources the API sends as links. */
internal fun HalResource.toEpisode(): Episode = decode(Episode.serializer()).copy(
    chaptersUrl = link("chapters"),
    transcripts = links("transcript").map { Transcript(it.href, it.type ?: "text/plain", it.hreflang) },
)

internal fun HalResource.toPodcast(): Podcast = decode(Podcast.serializer())

internal fun HalResource.toSubscriptionItem(): SubscriptionItem =
    SubscriptionItem(decode(Subscription.serializer()), embedded("podcast").single().toPodcast())
