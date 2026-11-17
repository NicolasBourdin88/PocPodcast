package fr.podcast.shared.repository

import fr.podcast.shared.account.Account
import fr.podcast.shared.api.ApiException
import fr.podcast.shared.api.HalResource
import fr.podcast.shared.api.PodcastApi
import fr.podcast.shared.api.expandUriTemplate
import fr.podcast.shared.api.halResource
import fr.podcast.shared.home.EpisodeItem
import fr.podcast.shared.model.Device
import fr.podcast.shared.model.Episode
import fr.podcast.shared.model.OpmlImportReport
import fr.podcast.shared.model.Podcast
import fr.podcast.shared.model.SearchResult
import fr.podcast.shared.model.Share
import fr.podcast.shared.model.SharePermission
import fr.podcast.shared.model.Subscription
import fr.podcast.shared.model.SubscriptionItem
import fr.podcast.shared.model.Transcript
import fr.podcast.shared.model.User
import fr.podcast.shared.model.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpResponse
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

    override suspend fun subscription(id: String): SubscriptionItem? = orNullIfNotFound { subscriptionResource(id).toSubscriptionItem() }

    override suspend fun subscribe(feedUrl: String): SubscriptionItem =
        subscriptionCollection().submit("subscribe", mapOf("feedUrl" to JsonPrimitive(feedUrl))).resource().toSubscriptionItem()

    override suspend fun updateSubscription(subscriptionId: String, note: String?, autoDownload: Boolean): SubscriptionItem {
        val values = mapOf("note" to note.orNull(), "autoDownload" to JsonPrimitive(autoDownload))
        return subscriptionResource(subscriptionId).submit("update", values).resource().toSubscriptionItem()
    }

    override suspend fun unsubscribe(subscriptionId: String) {
        subscriptionResource(subscriptionId).submit("unsubscribe")
    }

    override suspend fun refreshPodcast(podcastId: String): Podcast? {
        val podcast = orNullIfNotFound { api.get(link("podcast", mapOf("id" to podcastId))) } ?: return null
        if (podcast.template("refresh") == null) return podcast.toPodcast()
        return podcast.submit("refresh").resource().toPodcast()
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
        if (queue.template("clear-queue") != null) queue.submit("clear-queue", self = link("queue"))
    }

    override suspend fun exportOpml(): String =
        api.getText(subscriptionCollection().link("opml") ?: throw ApiException.Unexpected(200, "no opml link"), OPML)

    override suspend fun importOpml(opml: String): OpmlImportReport {
        val href = subscriptionCollection().link("opml") ?: throw ApiException.Unexpected(200, "no opml link")
        return api.postText(href, opml, OPML).decode(OpmlImportReport.serializer())
    }

    // --- AccountRepository ---

    override suspend fun me(): User = meResource().toUser()

    override suspend fun updateProfile(displayName: String): User =
        meResource().submit("update-profile", mapOf("displayName" to JsonPrimitive(displayName))).resource().toUser()

    override suspend fun changePassword(currentPassword: String, newPassword: String) {
        meResource().submit("change-password", mapOf("currentPassword" to JsonPrimitive(currentPassword), "newPassword" to JsonPrimitive(newPassword)))
    }

    override suspend fun devices(): List<Device> = deviceResources().map { it.decode(Device.serializer()) }

    override suspend fun revokeDevice(deviceId: String) {
        val device = deviceResources().firstOrNull { it.string("id") == deviceId } ?: throw ApiException.NotFound("device $deviceId")
        device.submit("revoke")
    }

    override suspend fun users(): List<User> = userCollection().embedded("users").map { it.toUser() }

    override suspend fun createUser(username: String, displayName: String, password: String, role: UserRole): User {
        val values = mapOf(
            "username" to JsonPrimitive(username),
            "displayName" to JsonPrimitive(displayName),
            "password" to JsonPrimitive(password),
            "role" to JsonPrimitive(role.name),
        )
        return userCollection().submit("create-user", values).resource().toUser()
    }

    override suspend fun updateUser(userId: String, displayName: String?, role: UserRole?): User {
        // Absent values: the template's, the current ones.
        val values = buildMap {
            displayName?.let { put("displayName", JsonPrimitive(it)) }
            role?.let { put("role", JsonPrimitive(it.name)) }
        }
        return userResource(userId).submit("update-user", values).resource().toUser()
    }

    override suspend fun resetPassword(userId: String, password: String) {
        userResource(userId).submit("reset-password", mapOf("password" to JsonPrimitive(password)))
    }

    override suspend fun deleteUser(userId: String) {
        userResource(userId).submit("delete-user")
    }

    // --- SharingRepository ---

    override suspend fun sharedWithMe(): List<SharedItem> = api.get(link("shared")).embedded("shares").map { share ->
        SharedItem(
            share = share.toShare(),
            subscription = share.embedded("subscription").firstOrNull()?.toSubscriptionItem(),
            episode = share.embedded("episode").firstOrNull()?.asEpisodeItem(),
        )
    }

    override suspend fun sharesOf(subscriptionId: String): List<Share> {
        val subscription = subscriptionResource(subscriptionId)
        val href = subscription.link("shares") ?: throw ApiException.NotOffered("shares")
        return api.get(href).embedded("shares").map { it.toShare() }
    }

    override suspend fun shareSubscription(subscriptionId: String, userId: String, permission: SharePermission, message: String?): Share {
        val values = mapOf("userId" to JsonPrimitive(userId), "permission" to JsonPrimitive(permission.name), "message" to message.orNull())
        return subscriptionResource(subscriptionId).submit("share", values).resource().toShare()
    }

    override suspend fun recommend(episodeId: String, userId: String, message: String?): Share {
        val episode = episodeResource(episodeId) ?: throw ApiException.NotFound("episode $episodeId")
        return episode.submit("recommend", mapOf("userId" to JsonPrimitive(userId), "message" to message.orNull())).resource().toShare()
    }

    override suspend fun changePermission(shareId: String, permission: SharePermission): Share =
        shareResource(shareId).submit("change-permission", mapOf("permission" to JsonPrimitive(permission.name))).resource().toShare()

    override suspend fun unshare(shareId: String) {
        shareResource(shareId).submit("unshare")
    }

    // --- Helpers ---

    private suspend fun subscriptionCollection(): HalResource = api.get(link("subscriptions"))

    private suspend fun subscriptionResource(id: String): HalResource = api.get(link("subscription", mapOf("id" to id)))

    private suspend fun queueResource(): HalResource = api.get(link("queue"))

    private suspend fun meResource(): HalResource = api.get(link("me"))

    private suspend fun deviceResources(): List<HalResource> =
        api.get(meResource().link("devices") ?: throw ApiException.NotOffered("devices")).embedded("devices")

    private suspend fun userCollection(): HalResource = api.get(link("users"))

    private suspend fun userResource(id: String): HalResource =
        userCollection().embedded("users").firstOrNull { it.string("id") == id } ?: throw ApiException.NotFound("user $id")

    private suspend fun shareResource(id: String): HalResource = api.get(link("share", mapOf("id" to id)))

    /** Submits the resource's [template]; refused with [ApiException.NotOffered] when the server does not offer it. */
    private suspend fun HalResource.submit(template: String, values: Map<String, JsonElement> = emptyMap(), self: String? = null): HttpResponse {
        val action = template(template) ?: throw ApiException.NotOffered(template)
        return api.submit(action, link("self") ?: self ?: throw ApiException.NotOffered(template), values)
    }

    private suspend fun HttpResponse.resource(): HalResource = halResource(api.serverUrl)

    /** An episode whose audio link is absolute, ready for a player. */
    private fun HalResource.asEpisode(): Episode = toEpisode().copy(audioUrl = link("audio")?.let(api::resolve))

    private fun HalResource.asEpisodeItem(): EpisodeItem = EpisodeItem(asEpisode(), embedded("podcast").single().toPodcast())

    /** Runs the episode's [template] if the server currently offers it. */
    private suspend fun perform(episodeId: String, template: String, values: Map<String, JsonElement> = emptyMap()) {
        val episode = episodeResource(episodeId) ?: return
        if (episode.template(template) == null) return
        episode.submit(template, values, self = link("episode", mapOf("id" to episodeId)))
    }

    private suspend fun episodeResource(id: String): HalResource? = orNullIfNotFound { api.get(link("episode", mapOf("id" to id))) }

    private inline fun <T> orNullIfNotFound(block: () -> T): T? =
        try {
            block()
        } catch (notFound: ApiException.NotFound) {
            null
        }

    private fun String?.orNull(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull

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

internal fun HalResource.toUser(): User = decode(User.serializer())

internal fun HalResource.toShare(): Share = decode(Share.serializer())
