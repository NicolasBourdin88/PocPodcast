package fr.podcast.server.catalog

import fr.podcast.server.auth.UserAccount
import fr.podcast.server.auth.access
import fr.podcast.server.db.PodcastDb
import fr.podcast.server.db.ServerDatabase
import fr.podcast.server.db.toKotlinInstant
import fr.podcast.shared.home.EpisodeItem
import fr.podcast.shared.model.Share
import fr.podcast.shared.model.SharePermission
import fr.podcast.shared.model.ShareType
import fr.podcast.shared.model.Subscription
import fr.podcast.shared.model.SubscriptionItem
import fr.podcast.shared.model.User
import fr.podcast.shared.model.UserRole
import java.time.OffsetDateTime

/** A subscription as one user sees it: whose it is, and what that user was given. */
data class SubscriptionView(val item: SubscriptionItem, val ownerId: Long, val permission: SharePermission?)

/** A share with the ids it points to, which clients get as links. */
data class ShareRecord(val share: Share, val resourceId: Long) {
    val ownerId: Long get() = share.owner.id.toLong()
    val granteeId: Long get() = share.grantee.id.toLong()
}

/** A share received, with its resource as the grantee sees it. */
data class SharedEntry(val record: ShareRecord, val subscription: SubscriptionItem? = null, val episode: EpisodeItem? = null)

/** Shares between users (ADR 0009): who sees what, and the shares themselves. Rights are checked with `can`. */
class Sharing(private val database: ServerDatabase) {

    /** The subscription if [viewer] may read it, with their access; null otherwise. */
    suspend fun subscription(viewer: UserAccount, id: String): SubscriptionView? {
        val subscriptionId = id.toLongOrNull() ?: return null
        return database.query { subscriptionView(viewer, subscriptionId) }
    }

    suspend fun updateSubscription(subscriptionId: String, note: String?, autoDownload: Boolean) {
        database.query { catalogQueries.updateSubscriptionById(note, autoDownload, subscriptionId.toLong()) }
    }

    /** Creates or updates the share; returns it. */
    suspend fun share(type: ShareType, resourceId: Long, ownerId: Long, granteeId: Long, permission: SharePermission, message: String?): ShareRecord =
        database.transaction {
            val id = sharesQueries.upsertShare(type.name, resourceId, ownerId, granteeId, permission.name, message).executeAsOne()
            sharesQueries.shareById(id, ::shareRecord).executeAsOne()
        }

    suspend fun record(id: String): ShareRecord? {
        val shareId = id.toLongOrNull() ?: return null
        return database.query { sharesQueries.shareById(shareId, ::shareRecord).executeAsOneOrNull() }
    }

    suspend fun sharesOf(type: ShareType, resourceId: Long): List<ShareRecord> =
        database.query { sharesQueries.sharesOfResource(type.name, resourceId, ::shareRecord).executeAsList() }

    suspend fun setPermission(shareId: Long, permission: SharePermission) {
        database.query { sharesQueries.updatePermission(permission.name, shareId) }
    }

    suspend fun delete(shareId: Long) {
        database.query { sharesQueries.deleteShare(shareId) }
    }

    /** What others share with [viewer], with the resources; a share whose resource is gone is left out. */
    suspend fun received(viewer: UserAccount): List<SharedEntry> {
        val records = database.query { sharesQueries.sharesReceived(viewer.id, ::shareRecord).executeAsList() }
        val catalog = UserCatalog(database, viewer.id)
        return records.mapNotNull { record ->
            when (record.share.type) {
                ShareType.SUBSCRIPTION ->
                    database.query { subscriptionView(viewer, record.resourceId) }?.let { SharedEntry(record, subscription = it.item) }
                ShareType.EPISODE -> catalog.episodeItem(record.resourceId.toString())?.let { SharedEntry(record, episode = it) }
                ShareType.PLAYLIST -> null // step 7
            }
        }
    }

    private fun PodcastDb.subscriptionView(viewer: UserAccount, subscriptionId: Long): SubscriptionView? {
        val row = catalogQueries.subscriptionById(subscriptionId).executeAsOneOrNull() ?: return null
        val permission = if (row.owner_id == viewer.id) {
            null
        } else {
            sharesQueries.permissionOf(ShareType.SUBSCRIPTION.name, subscriptionId, viewer.id).executeAsOneOrNull()?.let(SharePermission::valueOf)
        }
        val access = viewer.access(row.owner_id, permission) ?: return null
        val subscription = Subscription(
            id = row.id.toString(),
            note = row.note,
            subscribedAt = row.subscribed_at.toKotlinInstant(),
            autoDownload = row.auto_download,
            access = access,
            ownerName = row.owner_name.takeIf { row.owner_id != viewer.id },
        )
        val podcast = podcast(
            row.podcast_id, row.title, row.author, row.feed_url, row.artwork_url, row.description,
            row.website_url, row.language, row.refresh_status, row.last_refreshed_at,
        )
        return SubscriptionView(SubscriptionItem(subscription, podcast), row.owner_id, permission)
    }
}

@Suppress("FunctionParameterNaming")
private fun shareRecord(
    id: Long, resource_type: String, resource_id: Long, permission: String, message: String?, created_at: OffsetDateTime,
    owner_id: Long, owner_username: String, owner_display_name: String, owner_role: String,
    grantee_id: Long, grantee_username: String, grantee_display_name: String, grantee_role: String,
) = ShareRecord(
    Share(
        id = id.toString(),
        type = ShareType.valueOf(resource_type),
        permission = SharePermission.valueOf(permission),
        message = message,
        createdAt = created_at.toKotlinInstant(),
        owner = User(owner_id.toString(), owner_username, owner_display_name, UserRole.valueOf(owner_role)),
        grantee = User(grantee_id.toString(), grantee_username, grantee_display_name, UserRole.valueOf(grantee_role)),
    ),
    resource_id,
)
