package fr.podcast.server.hal

import fr.podcast.shared.model.Access
import fr.podcast.shared.model.Episode
import fr.podcast.shared.model.EpisodeStatus
import fr.podcast.shared.model.FileStatus
import fr.podcast.shared.model.Podcast
import fr.podcast.shared.model.SubscriptionItem
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * An action available on a resource in its current state. The same object is rendered as a
 * HAL-FORMS template for the SDK and as an htmx control for the thin client (ADR 0003).
 */
data class Affordance(
    val name: String,
    val method: HttpMethod,
    val target: String,
    val properties: List<AffordanceProperty> = emptyList(),
)

data class AffordanceProperty(
    val name: String,
    val type: String? = null,
    val required: Boolean = false,
    /** Filled in by the server ([value]); the client sends it back unchanged. */
    val readOnly: Boolean = false,
    val value: JsonPrimitive? = null,
    val min: Int? = null,
    val max: Int? = null,
    /** The allowed values (HAL-FORMS inline options), e.g. a role or a permission. */
    val options: List<String>? = null,
)

object Affordances {
    val createToken = Affordance(
        "create-token", HttpMethod.Post, "/auth/tokens",
        listOf(
            AffordanceProperty("username", required = true),
            AffordanceProperty("password", type = "password", required = true),
            AffordanceProperty("deviceName", required = true),
        ),
    )

    val logout = Affordance("logout", HttpMethod.Delete, "/auth/tokens/current")

    // Accounts (ADR 0009).
    val updateProfile = Affordance("update-profile", HttpMethod.Patch, "/me", listOf(AffordanceProperty("displayName", required = true)))

    val changePassword = Affordance(
        "change-password", HttpMethod.Put, "/me/password",
        listOf(
            AffordanceProperty("currentPassword", type = "password", required = true),
            AffordanceProperty("newPassword", type = "password", required = true),
        ),
    )

    val createUser = Affordance(
        "create-user", HttpMethod.Post, "/users",
        listOf(
            AffordanceProperty("username", required = true),
            AffordanceProperty("displayName", required = true),
            AffordanceProperty("password", type = "password", required = true),
            AffordanceProperty("role", value = JsonPrimitive("USER"), options = listOf("USER", "ADMIN")),
        ),
    )

    /** What an administrator can do to an account; not delete their own. */
    fun forUser(userId: Long, displayName: String, role: String, self: Boolean): List<Affordance> = buildList {
        add(
            Affordance(
                "update-user", HttpMethod.Patch, "/users/$userId",
                listOf(
                    AffordanceProperty("displayName", value = JsonPrimitive(displayName)),
                    AffordanceProperty("role", value = JsonPrimitive(role), options = listOf("USER", "ADMIN")),
                ),
            ),
        )
        add(Affordance("reset-password", HttpMethod.Put, "/users/$userId/password", listOf(AffordanceProperty("password", type = "password", required = true))))
        if (!self) add(Affordance("delete-user", HttpMethod.Delete, "/users/$userId"))
    }

    fun revokeDevice(deviceId: Long) = Affordance("revoke", HttpMethod.Delete, "/me/devices/$deviceId")

    val clearQueue = Affordance("clear-queue", HttpMethod.Delete, "/queue")

    val subscribe = Affordance(
        "subscribe", HttpMethod.Post, "/subscriptions",
        listOf(AffordanceProperty("feedUrl", type = "url", required = true)),
    )

    /** Subscribe to one search result: the feed address is already filled in. */
    fun subscribeTo(feedUrl: String) = Affordance(
        "subscribe", HttpMethod.Post, "/subscriptions",
        listOf(AffordanceProperty("feedUrl", type = "url", required = true, value = JsonPrimitive(feedUrl))),
    )

    /**
     * `update-progress` always; `mark-played` unless played; `mark-unplayed` unless new;
     * `keep` or `unkeep` (kept episodes are never purged); `favorite` or `unfavorite`; `rate`, and
     * `clear-rating` once rated (ADR 0010); `download` until the server has the file;
     * `recommend` to someone (ADR 0009); `enqueue` outside the queue, `dequeue` and `move` inside it.
     */
    fun forEpisode(episode: Episode): List<Affordance> = buildList {
        add(
            Affordance(
                "update-progress", HttpMethod.Put, "/episodes/${episode.id}/progress",
                listOf(
                    // No upper bound: the duration given by the feed is often approximate.
                    AffordanceProperty("positionSeconds", type = "number", required = true, value = JsonPrimitive(episode.positionSeconds), min = 0),
                ),
            ),
        )
        if (episode.status != EpisodeStatus.PLAYED) add(Affordance("mark-played", HttpMethod.Post, "/episodes/${episode.id}/played"))
        if (episode.status != EpisodeStatus.NEW) add(Affordance("mark-unplayed", HttpMethod.Delete, "/episodes/${episode.id}/played"))
        if (episode.kept) {
            add(Affordance("unkeep", HttpMethod.Delete, "/episodes/${episode.id}/kept"))
        } else {
            add(Affordance("keep", HttpMethod.Put, "/episodes/${episode.id}/kept"))
        }
        if (episode.favorite) {
            add(Affordance("unfavorite", HttpMethod.Delete, "/episodes/${episode.id}/favorite"))
        } else {
            add(Affordance("favorite", HttpMethod.Put, "/episodes/${episode.id}/favorite"))
        }
        add(
            Affordance(
                "rate", HttpMethod.Put, "/episodes/${episode.id}/rating",
                listOf(AffordanceProperty("rating", type = "number", required = true, value = episode.rating?.let(::JsonPrimitive), min = 1, max = 5)),
            ),
        )
        if (episode.rating != null) add(Affordance("clear-rating", HttpMethod.Delete, "/episodes/${episode.id}/rating"))
        if (episode.fileStatus == FileStatus.NONE || episode.fileStatus == FileStatus.FAILED) {
            add(Affordance("download", HttpMethod.Post, "/episodes/${episode.id}/download"))
        }
        add(recommend(episode.id))
        if (episode.inQueue) {
            add(Affordance("dequeue", HttpMethod.Delete, "/queue/${episode.id}"))
            add(Affordance("move", HttpMethod.Put, "/queue/${episode.id}", listOf(AffordanceProperty("position", type = "number", required = true, min = 0))))
        } else {
            add(
                Affordance(
                    "enqueue", HttpMethod.Post, "/queue",
                    listOf(
                        AffordanceProperty("episodeId", required = true, readOnly = true, value = JsonPrimitive(episode.id)),
                        // Absent: at the end of the queue; 0: next.
                        AffordanceProperty("position", type = "number", min = 0),
                    ),
                ),
            )
        }
    }

    /**
     * By the viewer's access (ADR 0009): `update` (note, automatic download) for the owner and a
     * WRITE share; `unsubscribe` and `share` for the owner; `subscribe` to follow it oneself.
     */
    fun forSubscription(item: SubscriptionItem): List<Affordance> = buildList {
        val subscription = item.subscription
        if (subscription.access != Access.READ) {
            add(
                Affordance(
                    "update", HttpMethod.Patch, "/subscriptions/${subscription.id}",
                    listOf(
                        AffordanceProperty("note", type = "textarea", value = JsonPrimitive(subscription.note)),
                        AffordanceProperty("autoDownload", type = "checkbox", value = JsonPrimitive(subscription.autoDownload)),
                    ),
                ),
            )
        }
        if (subscription.access == Access.OWNER) {
            add(Affordance("unsubscribe", HttpMethod.Delete, "/subscriptions/${subscription.id}"))
            add(
                Affordance(
                    "share", HttpMethod.Post, "/subscriptions/${subscription.id}/shares",
                    listOf(
                        AffordanceProperty("userId", required = true),
                        AffordanceProperty("permission", value = JsonPrimitive("READ"), options = listOf("READ", "WRITE")),
                        AffordanceProperty("message", type = "textarea"),
                    ),
                ),
            )
        }
        if (subscription.ownerName != null) add(subscribeTo(item.podcast.feedUrl))
    }

    /** Recommends an episode to someone (a READ share, ADR 0009). */
    fun recommend(episodeId: String) = Affordance(
        "recommend", HttpMethod.Post, "/episodes/$episodeId/shares",
        listOf(AffordanceProperty("userId", required = true), AffordanceProperty("message", type = "textarea")),
    )

    /**
     * `unshare` for the owner and the grantee (who may decline); `change-permission` for the owner,
     * except on a recommendation, which stays READ.
     */
    fun forShare(shareId: String, canManage: Boolean, recommendation: Boolean, permission: String): List<Affordance> = buildList {
        add(Affordance("unshare", HttpMethod.Delete, "/shares/$shareId"))
        if (canManage && !recommendation) {
            add(
                Affordance(
                    "change-permission", HttpMethod.Patch, "/shares/$shareId",
                    listOf(AffordanceProperty("permission", required = true, value = JsonPrimitive(permission), options = listOf("READ", "WRITE"))),
                ),
            )
        }
    }

    fun forPodcast(podcast: Podcast): List<Affordance> =
        listOf(Affordance("refresh", HttpMethod.Post, "/podcasts/${podcast.id}/refresh"))
}

/** `_templates` object of a HAL-FORMS document. */
fun List<Affordance>.toHalFormsTemplates(): JsonObject = buildJsonObject {
    for (affordance in this@toHalFormsTemplates) {
        putJsonObject(affordance.name) {
            put("method", affordance.method.value)
            put("target", affordance.target)
            put("contentType", "application/json")
            if (affordance.properties.isNotEmpty()) {
                putJsonArray("properties") {
                    for (property in affordance.properties) {
                        addJsonObject {
                            put("name", property.name)
                            property.type?.let { put("type", it) }
                            if (property.required) put("required", true)
                            if (property.readOnly) put("readOnly", true)
                            property.value?.let { put("value", it) }
                            property.min?.let { put("min", it) }
                            property.max?.let { put("max", it) }
                            property.options?.let { options ->
                                putJsonObject("options") {
                                    putJsonArray("inline") { options.forEach { add(JsonPrimitive(it)) } }
                                    put("maxItems", 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
