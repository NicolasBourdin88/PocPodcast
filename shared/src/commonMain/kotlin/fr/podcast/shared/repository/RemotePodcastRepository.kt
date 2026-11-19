package fr.podcast.shared.repository

import kotlinx.serialization.json.JsonArray
import fr.podcast.shared.model.ListeningStats
import fr.podcast.shared.model.EpisodeQuery
import fr.podcast.shared.model.EpisodePage
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
import fr.podcast.shared.model.Playlist
import fr.podcast.shared.model.PlaylistKind
import fr.podcast.shared.model.PlaylistRules
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

    override suspend fun updateSubscription(subscriptionId: String, note: String?, autoDownload: Boolean, tags: List<String>?): SubscriptionItem {
        val values = buildMap {
            put("note", note.orNull())
            put("autoDownload", JsonPrimitive(autoDownload))
            tags?.let { put("tags", JsonArray(it.map(::JsonPrimitive))) }
        }
        return subscriptionResource(subscriptionId).submit("update", values).resource().toSubscriptionItem()
    }

    override suspend fun searchEpisodes(query: EpisodeQuery, limit: Int, offset: Int): EpisodePage {
        val values = mapOf(
            "q" to query.text.trim().ifEmpty { null },
            "status" to query.status?.name,
            "favorite" to "true".takeIf { query.favoritesOnly },
            "minRating" to query.minRating.takeIf { it > 0 },
            "from" to query.from?.toString(),
            "to" to query.to?.toString(),
            "podcast" to query.podcastId,
            "tag" to query.tag,
            "offset" to offset.takeIf { it > 0 },
            "limit" to limit.coerceIn(1, MAX_PAGE),
        )
        val page = api.get(link("episodes", values))
        val items = page.embedded("episodes").map { it.asEpisodeItem() }
        return EpisodePage(items, page.int("total") ?: items.size)
    }

    override suspend fun listeningStats(): ListeningStats =
        api.get(meResource().link("stats") ?: throw ApiException.NotOffered("stats")).decode(ListeningStats.serializer())

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

    override suspend fun setFavorite(episodeId: String, favorite: Boolean) = perform(episodeId, if (favorite) "favorite" else "unfavorite")

    override suspend fun rate(episodeId: String, rating: Int?) =
        if (rating == null) perform(episodeId, "clear-rating") else perform(episodeId, "rate", mapOf("rating" to JsonPrimitive(rating)))

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
        if (queue.template("clear-queue") != null) queue.submit("clear-queue", resource = link("queue"))
    }

    override suspend fun exportOpml(): String =
        api.getText(subscriptionCollection().link("opml") ?: throw ApiException.Unexpected(200, "no opml link"), OPML)

    override suspend fun importOpml(opml: String): OpmlImportReport {
        val href = subscriptionCollection().link("opml") ?: throw ApiException.Unexpected(200, "no opml link")
        return api.postText(href, opml, OPML).decode(OpmlImportReport.serializer())
    }

    // --- AccountRepository ---

    override suspend fun me(): User = meResource().toUser()

    override suspend fun updateProfile(displayName: String?, avatar: String?): User {
        val values = buildMap {
            displayName?.let { put("displayName", JsonPrimitive(it)) }
            avatar?.let { put("avatar", JsonPrimitive(it)) }
        }
        return meResource().submit("update-profile", values).resource().toUser()
    }

    override suspend fun changePassword(currentPassword: String, newPassword: String) {
        meResource().submit("change-password", mapOf("currentPassword" to JsonPrimitive(currentPassword), "newPassword" to JsonPrimitive(newPassword)))
    }

    override suspend fun devices(): List<Device> = deviceResources().map { it.decode(Device.serializer()) }

    override suspend fun revokeDevice(deviceId: String) {
        val device = deviceResources().firstOrNull { it.string("id") == deviceId } ?: throw ApiException.NotFound("device $deviceId")
        device.submit("revoke")
    }

    override suspend fun createAppPassword(name: String): String =
        deviceCollection().submit("create-app-password", mapOf("name" to JsonPrimitive(name))).resource().string("password")
            ?: throw ApiException.NotOffered("create-app-password")

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
            playlist = share.embedded("playlist").firstOrNull()?.toPlaylist(),
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

    override suspend fun sharePlaylist(playlistId: String, userId: String, permission: SharePermission, message: String?): Share {
        val values = mapOf("userId" to JsonPrimitive(userId), "permission" to JsonPrimitive(permission.name), "message" to message.orNull())
        return playlistResource(playlistId).submit("share", values).resource().toShare()
    }

    override suspend fun sharesOfPlaylist(playlistId: String): List<Share> {
        val href = playlistResource(playlistId).link("shares") ?: throw ApiException.NotOffered("shares")
        return api.get(href).embedded("shares").map { it.toShare() }
    }

    // --- PlaylistRepository ---

    override suspend fun playlists(): List<Playlist> = api.get(link("playlists")).embedded("playlists").map { it.toPlaylist() }

    override suspend fun playlist(id: String): PlaylistDetails? = orNullIfNotFound { playlistResource(id).toPlaylistDetails() }

    override suspend fun createPlaylist(title: String, description: String?, rules: PlaylistRules?): PlaylistDetails {
        val kind = if (rules == null) PlaylistKind.STATIC else PlaylistKind.DYNAMIC
        val values = playlistValues(title, description, rules) + ("kind" to JsonPrimitive(kind.name))
        return api.get(link("playlists")).submit("create-playlist", values).resource().toPlaylistDetails()
    }

    override suspend fun updatePlaylist(id: String, title: String, description: String?, rules: PlaylistRules?): PlaylistDetails =
        playlistResource(id).submit("update-playlist", playlistValues(title, description, rules)).resource().toPlaylistDetails()

    override suspend fun deletePlaylist(id: String) {
        playlistResource(id).submit("delete-playlist")
    }

    override suspend fun addToPlaylist(playlistId: String, episodeId: String, position: Int?) {
        val values = buildMap {
            put("episodeId", JsonPrimitive(episodeId))
            position?.let { put("position", JsonPrimitive(it)) }
        }
        playlistResource(playlistId).submit("add-episode", values)
    }

    override suspend fun removeFromPlaylist(playlistId: String, episodeId: String) {
        playlistEpisode(playlistId, episodeId).submit("remove-from-playlist")
    }

    override suspend fun moveInPlaylist(playlistId: String, episodeId: String, position: Int) {
        playlistEpisode(playlistId, episodeId).submit("move-in-playlist", mapOf("position" to JsonPrimitive(position)))
    }

    override suspend fun shufflePlaylist(id: String): PlaylistDetails = playlistResource(id).submit("shuffle").resource().toPlaylistDetails()

    override suspend fun publishFeed(playlistId: String): String =
        playlistResource(playlistId).submit("publish-feed").resource().toPlaylist().feedUrl
            ?: throw ApiException.Unexpected(200, "no feed link after publish-feed")

    override suspend fun unpublishFeed(playlistId: String) {
        playlistResource(playlistId).submit("unpublish-feed")
    }

    // --- Helpers ---

    private suspend fun subscriptionCollection(): HalResource = api.get(link("subscriptions"))

    private suspend fun subscriptionResource(id: String): HalResource = api.get(link("subscription", mapOf("id" to id)))

    private suspend fun queueResource(): HalResource = api.get(link("queue"))

    private suspend fun meResource(): HalResource = api.get(link("me"))

    private suspend fun deviceCollection(): HalResource =
        api.get(meResource().link("devices") ?: throw ApiException.NotOffered("devices"))

    private suspend fun deviceResources(): List<HalResource> = deviceCollection().embedded("devices")

    private suspend fun userCollection(): HalResource = api.get(link("users"))

    private suspend fun userResource(id: String): HalResource =
        userCollection().embedded("users").firstOrNull { it.string("id") == id } ?: throw ApiException.NotFound("user $id")

    private suspend fun shareResource(id: String): HalResource = api.get(link("share", mapOf("id" to id)))

    private suspend fun playlistResource(id: String): HalResource = api.get(link("playlist", mapOf("id" to id)))

    /** An episode of the playlist, with the playlist's actions on it. */
    private suspend fun playlistEpisode(playlistId: String, episodeId: String): HalResource =
        playlistResource(playlistId).embedded("episodes").firstOrNull { it.string("id") == episodeId }
            ?: throw ApiException.NotFound("episode $episodeId in playlist $playlistId")

    /** The fields of the playlist templates; the rules only for a dynamic playlist. */
    private fun playlistValues(title: String, description: String?, rules: PlaylistRules?): Map<String, JsonElement> = buildMap {
        put("title", JsonPrimitive(title))
        put("description", description.orNull())
        rules?.let {
            put("keywords", JsonPrimitive(it.keywords.joinToString(", ")))
            put("favoritesOnly", JsonPrimitive(it.favoritesOnly))
            put("minRating", JsonPrimitive(it.minRating))
            put("unplayedOnly", JsonPrimitive(it.unplayedOnly))
            put("order", JsonPrimitive(it.order.name))
            put("limit", JsonPrimitive(it.limit))
        }
    }

    /** With its feed address made absolute. */
    private fun HalResource.toPlaylist(): Playlist = decode(Playlist.serializer()).copy(feedUrl = link("feed")?.let(api::resolve))

    private fun HalResource.toPlaylistDetails(): PlaylistDetails = PlaylistDetails(toPlaylist(), embedded("episodes").map { it.asEpisodeItem() })

    /** Submits the resource's [template]; refused with [ApiException.NotOffered] when the server does not offer it. */
    private suspend fun HalResource.submit(template: String, values: Map<String, JsonElement> = emptyMap(), resource: String? = null): HttpResponse {
        val action = template(template) ?: throw ApiException.NotOffered(template)
        return api.submit(action, link("self") ?: resource ?: throw ApiException.NotOffered(template), values)
    }

    private suspend fun HttpResponse.resource(): HalResource = halResource(api.serverUrl)

    /** An episode whose audio link is absolute, ready for a player. */
    private fun HalResource.asEpisode(): Episode = toEpisode().copy(audioUrl = link("audio")?.let(api::resolve))

    private fun HalResource.asEpisodeItem(): EpisodeItem = EpisodeItem(asEpisode(), embedded("podcast").single().toPodcast())

    /** Runs the episode's [template] if the server currently offers it. */
    private suspend fun perform(episodeId: String, template: String, values: Map<String, JsonElement> = emptyMap()) {
        val episode = episodeResource(episodeId) ?: return
        if (episode.template(template) == null) return
        episode.submit(template, values, resource = link("episode", mapOf("id" to episodeId)))
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
