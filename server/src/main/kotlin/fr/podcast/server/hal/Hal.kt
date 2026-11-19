package fr.podcast.server.hal

import fr.podcast.shared.model.ListeningStats
import fr.podcast.server.auth.IssuedToken
import fr.podcast.server.auth.Device
import fr.podcast.server.auth.Role
import fr.podcast.server.auth.Action
import fr.podcast.server.auth.UserAccount
import fr.podcast.server.auth.can
import fr.podcast.server.catalog.ShareRecord
import fr.podcast.server.catalog.SharedEntry
import fr.podcast.server.catalog.Page
import fr.podcast.server.catalog.PlaylistView
import fr.podcast.server.media.AudioLinks
import fr.podcast.server.search.SearchHit
import fr.podcast.server.search.normalizedFeed
import io.ktor.http.encodeURLParameter
import fr.podcast.shared.home.EpisodeItem
import fr.podcast.shared.home.HomeState
import fr.podcast.shared.model.Access
import fr.podcast.shared.model.Episode
import fr.podcast.shared.model.Share
import fr.podcast.shared.model.ShareType
import fr.podcast.shared.model.OpmlImportReport
import fr.podcast.shared.model.Playlist
import fr.podcast.shared.model.PlaylistKind
import fr.podcast.shared.model.Podcast
import fr.podcast.shared.model.SearchResult
import fr.podcast.shared.model.Subscription
import fr.podcast.shared.model.SubscriptionItem
import fr.podcast.shared.model.User
import fr.podcast.shared.model.UserRole
import fr.podcast.shared.model.Device as ModelDevice
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/*
 * HAL (+ HAL-FORMS when `forms` is true) representations, see docs/api/conventions.md
 * and the OpenAPI schemas. Model fields come from the :shared serializers, so the SDK
 * reads exactly what the server writes.
 */

const val API_VERSION = 1

// Every field is written, including defaults and nulls: the contract lists them as required.
private val modelJson = Json {
    encodeDefaults = true
    explicitNulls = true
}

/** A HAL link; [type] and [hreflang] describe external resources (transcripts…). */
data class Link(val href: String, val type: String? = null, val hreflang: String? = null)

private fun <T> JsonObjectBuilder.fields(serializer: KSerializer<T>, value: T) {
    modelJson.encodeToJsonElement(serializer, value).jsonObject.forEach { (key, element) -> put(key, element) }
}

private fun JsonObjectBuilder.link(link: Link) {
    put("href", link.href)
    if ('{' in link.href) put("templated", true)
    link.type?.let { put("type", it) }
    link.hreflang?.let { put("hreflang", it) }
}

/** `_links`: one link per relation, or an array when a relation has several (transcripts). */
private fun JsonObjectBuilder.links(
    single: List<Pair<String, String>>,
    extra: List<Pair<String, Link>> = emptyList(),
    multiple: Map<String, List<Link>> = emptyMap(),
) {
    putJsonObject("_links") {
        for ((rel, href) in single) putJsonObject(rel) { link(Link(href)) }
        for ((rel, link) in extra) putJsonObject(rel) { link(link) }
        for ((rel, links) in multiple) {
            if (links.isNotEmpty()) putJsonArray(rel) { links.forEach { addJsonObject { link(it) } } }
        }
    }
}

private fun JsonObjectBuilder.links(vararg single: Pair<String, String>) = links(single.toList())

private fun JsonObjectBuilder.templates(forms: Boolean, affordances: List<Affordance>) {
    if (forms && affordances.isNotEmpty()) put("_templates", affordances.toHalFormsTemplates())
}

fun rootResource(serverName: String, user: UserAccount?, home: HomeState?, forms: Boolean, audio: AudioLinks): JsonObject = buildJsonObject {
    put("name", serverName)
    put("apiVersion", API_VERSION)
    if (user == null) {
        links("self" to "/", "tokens" to "/auth/tokens")
        templates(forms, listOf(Affordances.createToken))
    } else {
        links(
            "self" to "/",
            "tokens" to "/auth/tokens",
            "me" to "/me",
            "subscriptions" to "/subscriptions",
            "queue" to "/queue",
            "users" to "/users",
            "shared" to "/shared",
            "playlists" to "/playlists",
            "episodes" to "/episodes{?q,status,favorite,minRating,from,to,podcast,tag,offset,limit}",
            "episode" to "/episodes/{id}",
            "podcast" to "/podcasts/{id}",
            "subscription" to "/subscriptions/{id}",
            "share" to "/shares/{id}",
            "playlist" to "/playlists/{id}",
            "search" to "/search{?q}",
        )
        if (home != null) {
            putJsonObject("_embedded") {
                put("resume", buildJsonArray { home.resume.forEach { add(episodeResource(it, forms, audio)) } })
                put("latest", buildJsonArray { home.latest.forEach { add(episodeResource(it, forms, audio)) } })
            }
        }
    }
}

/** A new app password: its only appearance; it is then one of the devices (ADR 0011). */
fun appPasswordResource(token: IssuedToken): JsonObject = buildJsonObject {
    put("name", token.deviceName)
    put("password", token.value)
    put("createdAt", token.createdAt.toString())
    links("self" to "/me/devices")
}

fun tokenResource(token: IssuedToken): JsonObject = buildJsonObject {
    put("token", token.value)
    put("deviceName", token.deviceName)
    put("createdAt", token.createdAt.toString())
    links("self" to "/auth/tokens/current", "me" to "/me")
}

fun UserAccount.toUser() = User(id.toString(), username, displayName, UserRole.valueOf(role.name), avatar)

/** How much the user listened (ADR 0012). */
fun statsResource(stats: ListeningStats): JsonObject = buildJsonObject {
    fields(ListeningStats.serializer(), stats)
    links("self" to "/me/stats")
}

/** The signed-in user: profile, password, devices, sign-out. */
fun meResource(user: UserAccount, forms: Boolean): JsonObject = buildJsonObject {
    fields(User.serializer(), user.toUser())
    links("self" to "/me", "devices" to "/me/devices", "users" to "/users", "stats" to "/me/stats")
    templates(forms, listOf(Affordances.updateProfile, Affordances.changePassword, Affordances.logout))
}

/** An account as [viewer] sees it; administrators get the management templates. */
fun userResource(user: UserAccount, viewer: UserAccount, forms: Boolean): JsonObject = buildJsonObject {
    fields(User.serializer(), user.toUser())
    links("self" to "/users/${user.id}")
    if (viewer.role == Role.ADMIN) templates(forms, Affordances.forUser(user.id, user.displayName, user.role.name, self = user.id == viewer.id))
}

/** Every account of the server: whom to share with; administrators can create accounts. */
fun userCollection(users: List<UserAccount>, viewer: UserAccount, forms: Boolean): JsonObject = buildJsonObject {
    put("count", users.size)
    links("self" to "/users")
    putJsonObject("_embedded") {
        put("users", buildJsonArray { users.forEach { add(userResource(it, viewer, forms)) } })
    }
    if (viewer.role == Role.ADMIN) templates(forms, listOf(Affordances.createUser))
}

/** The devices signed in to the account, each with its `revoke` template; app passwords are created here. */
fun deviceCollection(devices: List<Device>, forms: Boolean): JsonObject = buildJsonObject {
    put("count", devices.size)
    links("self" to "/me/devices")
    templates(forms, listOf(Affordances.createAppPassword))
    putJsonObject("_embedded") {
        put(
            "devices",
            buildJsonArray {
                for (device in devices) {
                    add(
                        buildJsonObject {
                            fields(ModelDevice.serializer(), ModelDevice(device.id.toString(), device.name, device.createdAt, device.lastUsedAt, device.current))
                            links("self" to "/me/devices/${device.id}")
                            templates(forms, listOf(Affordances.revokeDevice(device.id)))
                        },
                    )
                }
            },
        )
    }
}

fun podcastResource(podcast: Podcast, forms: Boolean = false): JsonObject = buildJsonObject {
    fields(Podcast.serializer(), podcast)
    links(
        "self" to "/podcasts/${podcast.id}",
        "episodes" to "/podcasts/${podcast.id}/episodes{?offset,limit}",
        "feed" to podcast.feedUrl,
    )
    templates(forms, Affordances.forPodcast(podcast))
}

/** A subscription as the viewer sees it: its templates follow their access (ADR 0009). */
fun subscriptionResource(item: SubscriptionItem, forms: Boolean): JsonObject = buildJsonObject {
    val id = item.subscription.id
    fields(Subscription.serializer(), item.subscription)
    links(
        buildList {
            add("self" to "/subscriptions/$id")
            add("podcast" to "/podcasts/${item.podcast.id}")
            if (item.subscription.access == Access.OWNER) add("shares" to "/subscriptions/$id/shares")
        },
    )
    putJsonObject("_embedded") { put("podcast", podcastResource(item.podcast)) }
    templates(forms, Affordances.forSubscription(item))
}

/**
 * A share; [resource] embeds what is shared, as the viewer sees it. The owner and the grantee may
 * remove it (`unshare`); the owner may change its permission, except on a recommendation.
 */
fun shareResource(record: ShareRecord, viewer: UserAccount, forms: Boolean, resource: JsonObject? = null): JsonObject = buildJsonObject {
    val share = record.share
    fields(Share.serializer(), share)
    val target = when (share.type) {
        ShareType.SUBSCRIPTION -> "subscription" to "/subscriptions/${record.resourceId}"
        ShareType.EPISODE -> "episode" to "/episodes/${record.resourceId}"
        ShareType.PLAYLIST -> "playlist" to "/playlists/${record.resourceId}"
    }
    links("self" to "/shares/${share.id}", target)
    resource?.let { putJsonObject("_embedded") { put(target.first, it) } }
    val manage = viewer.can(Action.SHARE, record.ownerId, null)
    templates(forms, Affordances.forShare(share.id, manage, share.type == ShareType.EPISODE, share.permission.name))
}

/** Whom a subscription is shared with, and the `share` template to add someone. */
fun subscriptionShares(item: SubscriptionItem, records: List<ShareRecord>, viewer: UserAccount, forms: Boolean): JsonObject = buildJsonObject {
    put("count", records.size)
    links("self" to "/subscriptions/${item.subscription.id}/shares", "subscription" to "/subscriptions/${item.subscription.id}")
    putJsonObject("_embedded") {
        put("shares", buildJsonArray { records.forEach { add(shareResource(it, viewer, forms)) } })
    }
    templates(forms, Affordances.forSubscription(item).filter { it.name == "share" })
}

/** What others share with the viewer, most recent first, each with what is shared. */
fun sharedWithMe(entries: List<SharedEntry>, viewer: UserAccount, forms: Boolean, audio: AudioLinks): JsonObject = buildJsonObject {
    put("count", entries.size)
    links("self" to "/shared")
    putJsonObject("_embedded") {
        put(
            "shares",
            buildJsonArray {
                for (entry in entries) {
                    val resource = entry.subscription?.let { subscriptionResource(it, forms) }
                        ?: entry.episode?.let { episodeResource(it, forms, audio) }
                        ?: entry.playlist?.let { playlistResource(it, viewer, forms, audio) }
                    add(shareResource(entry.record, viewer, forms, resource))
                }
            },
        )
    }
}

fun subscriptionCollection(items: List<SubscriptionItem>, forms: Boolean): JsonObject = buildJsonObject {
    put("count", items.size)
    links("self" to "/subscriptions", "opml" to "/subscriptions/opml")
    putJsonObject("_embedded") {
        put("subscriptions", buildJsonArray { items.forEach { add(subscriptionResource(it, forms)) } })
    }
    templates(forms, listOf(Affordances.subscribe))
}

/**
 * An episode; its `audio` link is signed, for players that cannot send credentials (ADR 0008).
 * [extra] adds actions that depend on where it is shown (in a playlist…).
 */
fun episodeResource(item: EpisodeItem, forms: Boolean, audio: AudioLinks, extra: List<Affordance> = emptyList()): JsonObject = buildJsonObject {
    val episode: Episode = item.episode
    fields(Episode.serializer(), episode)
    links(
        single = listOf(
            "self" to "/episodes/${episode.id}",
            "podcast" to "/podcasts/${episode.podcastId}",
            "audio" to audio.path(episode.id),
        ),
        // Podcast 2.0: external resources announced by the feed.
        extra = listOfNotNull(episode.chaptersUrl?.let { "chapters" to Link(it, type = "application/json+chapters") }),
        multiple = mapOf("transcript" to episode.transcripts.map { Link(it.url, it.type, it.language) }),
    )
    putJsonObject("_embedded") { put("podcast", podcastResource(item.podcast)) }
    templates(forms, Affordances.forEpisode(episode) + extra)
}

/**
 * A playlist as [viewer] sees it (ADR 0010); with [episodes], its content, each episode with the
 * viewer's own state, and in a static playlist they may modify, its `remove-from-playlist` and
 * `move-in-playlist` templates.
 */
fun playlistResource(view: PlaylistView, viewer: UserAccount, forms: Boolean, audio: AudioLinks, episodes: List<EpisodeItem>? = null): JsonObject =
    buildJsonObject {
        val playlist: Playlist = view.playlist
        val canModify = viewer.can(Action.MODIFY, view.ownerId, view.permission)
        val owner = viewer.can(Action.SHARE, view.ownerId, view.permission)
        fields(Playlist.serializer(), playlist)
        links(
            single = buildList {
                add("self" to "/playlists/${playlist.id}")
                if (owner) add("shares" to "/playlists/${playlist.id}/shares")
            },
            // Its RSS feed, which only the owner sees (ADR 0010).
            extra = listOfNotNull(view.feedSecret?.takeIf { owner }?.let { "feed" to Link("/feeds/$it", type = "application/rss+xml") }),
        )
        episodes?.let { items ->
            val editable = canModify && playlist.kind == PlaylistKind.STATIC
            putJsonObject("_embedded") {
                put(
                    "episodes",
                    buildJsonArray {
                        items.forEach { item ->
                            val extra = if (editable) Affordances.inPlaylist(playlist.id, item.episode.id) else emptyList()
                            add(episodeResource(item, forms, audio, extra))
                        }
                    },
                )
            }
        }
        templates(forms, Affordances.forPlaylist(playlist, canModify, owner, published = view.feedSecret != null))
    }

/** Whom a playlist is shared with, and the `share` template to add someone. */
fun playlistShares(view: PlaylistView, records: List<ShareRecord>, viewer: UserAccount, forms: Boolean): JsonObject = buildJsonObject {
    val id = view.playlist.id
    put("count", records.size)
    links("self" to "/playlists/$id/shares", "playlist" to "/playlists/$id")
    putJsonObject("_embedded") {
        put("shares", buildJsonArray { records.forEach { add(shareResource(it, viewer, forms)) } })
    }
    templates(forms, Affordances.forPlaylist(view.playlist, canModify = false, owner = true).filter { it.name == "share" })
}

/** The user's own playlists; those shared with them are in /shared. */
fun playlistCollection(views: List<PlaylistView>, viewer: UserAccount, forms: Boolean, audio: AudioLinks): JsonObject = buildJsonObject {
    put("count", views.size)
    links("self" to "/playlists")
    putJsonObject("_embedded") {
        put("playlists", buildJsonArray { views.forEach { add(playlistResource(it, viewer, forms, audio)) } })
    }
    templates(forms, listOf(Affordances.createPlaylist))
}

/** A page of episodes; [path] plus [query] (without paging) rebuild the page links. */
fun episodeCollection(page: Page<EpisodeItem>, path: String, query: Map<String, String>, forms: Boolean, audio: AudioLinks): JsonObject = buildJsonObject {
    fun pageHref(offset: Int) = path + "?" + (query + mapOf("offset" to "$offset", "limit" to "${page.limit}"))
        .entries.joinToString("&") { (key, value) -> "$key=${value.encodeURLParameter()}" }

    put("count", page.items.size)
    put("total", page.total)
    put("offset", page.offset)
    put("limit", page.limit)
    links(
        buildList {
            add("self" to pageHref(page.offset))
            if (page.offset + page.limit < page.total) add("next" to pageHref(page.offset + page.limit))
            if (page.offset > 0) add("prev" to pageHref(maxOf(0, page.offset - page.limit)))
        },
    )
    putJsonObject("_embedded") {
        put("episodes", buildJsonArray { page.items.forEach { add(episodeResource(it, forms, audio)) } })
    }
}

/** The user's queue, in listening order; each episode carries its `dequeue` and `move` templates. */
fun queueResource(items: List<EpisodeItem>, forms: Boolean, audio: AudioLinks): JsonObject = buildJsonObject {
    put("count", items.size)
    links("self" to "/queue")
    putJsonObject("_embedded") {
        put("episodes", buildJsonArray { items.forEach { add(episodeResource(it, forms, audio)) } })
    }
    templates(forms, if (items.isEmpty()) emptyList() else listOf(Affordances.clearQueue))
}

fun opmlImportResource(report: OpmlImportReport): JsonObject = buildJsonObject {
    fields(OpmlImportReport.serializer(), report)
    links("subscriptions" to "/subscriptions")
}

/** Search results; each one can be subscribed to with its pre-filled `subscribe` template. */
fun searchResults(query: String, hits: List<SearchHit>, answeredBy: List<String>, subscribed: Set<String>, forms: Boolean): JsonObject = buildJsonObject {
    put("query", query)
    put("count", hits.size)
    putJsonArray("sources") { answeredBy.forEach { add(JsonPrimitive(it)) } }
    links("self" to "/search?q=${query.encodeURLParameter()}")
    putJsonObject("_embedded") {
        put(
            "results",
            buildJsonArray {
                for (hit in hits) {
                    val isSubscribed = normalizedFeed(hit.feedUrl) in subscribed
                    add(
                        buildJsonObject {
                            fields(SearchResult.serializer(), SearchResult(hit.title, hit.author, hit.feedUrl, hit.artworkUrl, hit.description, hit.source, isSubscribed))
                            links("feed" to hit.feedUrl)
                            templates(forms, if (isSubscribed) emptyList() else listOf(Affordances.subscribeTo(hit.feedUrl)))
                        },
                    )
                }
            },
        )
    }
}
