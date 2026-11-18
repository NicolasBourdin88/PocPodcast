package fr.podcast.server.sync

import fr.podcast.server.auth.UserAccount
import fr.podcast.server.catalog.UserCatalog
import fr.podcast.server.db.ServerDatabase
import fr.podcast.server.feeds.FeedException
import fr.podcast.server.feeds.code
import fr.podcast.server.feeds.UrlGuard
import fr.podcast.server.opml.OpmlEntry
import fr.podcast.server.search.normalizedFeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/** A subscription of the Open Podcast API (v1), absent fields left out. */
@Serializable
data class OpaSubscription(
    val feed_url: String,
    val guid: String,
    val is_subscribed: Boolean,
    val subscription_changed: String? = null,
    val guid_changed: String? = null,
    val new_guid: String? = null,
    val deleted: String? = null,
)

@Serializable
data class OpaPage(
    val total: Int,
    val page: Int,
    val per_page: Int,
    val next: String? = null,
    val previous: String? = null,
    val subscriptions: List<OpaSubscription>,
)

@Serializable
data class OpaNewSubscription(val feed_url: String, val guid: String? = null)

@Serializable
data class OpaNewSubscriptions(val subscriptions: List<OpaNewSubscription>)

@Serializable
data class OpaFailure(val feed_url: String, val message: String)

@Serializable
data class OpaAdded(val success: List<OpaSubscription>, val failure: List<OpaFailure>)

@Serializable
data class OpaUpdate(val new_feed_url: String? = null, val new_guid: String? = null, val is_subscribed: Boolean? = null)

@Serializable
data class OpaUpdated(
    val new_feed_url: String? = null,
    val is_subscribed: Boolean? = null,
    val subscription_changed: String? = null,
    val guid_changed: String? = null,
    val new_guid: String? = null,
)

@Serializable
data class OpaDeletion(val deletion_id: Long, val message: String)

@Serializable
data class OpaDeletionStatus(val deletion_id: Long, val status: String, val message: String? = null)

/** An entry looked for by its GUID: found, deleted (410), or unknown (404). */
sealed interface OpaLookup {
    data class Found(val subscription: OpaSubscription) : OpaLookup
    data object Deleted : OpaLookup
    data object Unknown : OpaLookup
}

/**
 * The subscriptions of the Open Podcast API (ADR 0011), on the same change log as the gPodder API:
 * whatever a client does, the others see it. A subscription is named by the GUID its client sent,
 * otherwise by the `podcast:guid` of its feed URL.
 */
class OpenPodcastApi(
    private val database: ServerDatabase,
    private val guard: UrlGuard,
    private val refreshSoon: (List<Long>) -> Unit,
    private val removePodcast: suspend (Long) -> Unit,
) {
    /** Since [since], the entries changed or deleted; otherwise every entry not deleted. */
    suspend fun subscriptions(user: UserAccount, since: OffsetDateTime?, page: Int, perPage: Int): OpaPage {
        val entries = database.query {
            if (since == null) {
                syncQueries.subscriptionEntries(user.id).executeAsList().map { entry(it.feed_url, it.subscribed, it.changed_at, it.guid, it.deleted_at) }
            } else {
                syncQueries.subscriptionEntriesSince(user.id, since).executeAsList().map { entry(it.feed_url, it.subscribed, it.changed_at, it.guid, it.deleted_at) }
            }
        }
        val pages = entries.chunked(perPage)
        fun link(number: Int) = buildString {
            append("/v1/subscriptions?page=$number&per_page=$perPage")
            since?.let { append("&since=").append(it.iso()) }
        }
        return OpaPage(
            total = entries.size,
            page = page,
            per_page = perPage,
            next = link(page + 1).takeIf { page < pages.size },
            previous = link(page - 1).takeIf { page > 1 },
            subscriptions = pages.getOrNull(page - 1).orEmpty(),
        )
    }

    /** Subscribes as an OPML import does; a feed already followed is a success too. */
    suspend fun add(user: UserAccount, requested: List<OpaNewSubscription>): OpaAdded {
        val failures = mutableListOf<OpaFailure>()
        val accepted = withContext(Dispatchers.IO) {
            requested.distinctBy { it.feed_url }.mapNotNull { wanted ->
                val url = wanted.feed_url.trim()
                when {
                    !url.contains("://") -> null.also { failures += OpaFailure(wanted.feed_url, "No protocol present") }
                    !url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true) ->
                        null.also { failures += OpaFailure(wanted.feed_url, "Only http and https feeds are supported") }
                    else -> try {
                        guard.check(url)
                        wanted.copy(feed_url = url)
                    } catch (refused: FeedException) {
                        null.also { failures += OpaFailure(wanted.feed_url, "Feed address refused (${refused.code})") }
                    }
                }
            }
        }
        val import = UserCatalog(database, user.id).importFeeds(accepted.map { OpmlEntry(it.feed_url, it.feed_url) }, guard)
        refreshSoon(import.newPodcasts)
        val refused = import.report.failed.associateBy { it.feedUrl }
        val successes = accepted.mapNotNull { wanted ->
            refused[wanted.feed_url]?.let { failures += OpaFailure(wanted.feed_url, it.reason); return@mapNotNull null }
            database.query {
                wanted.guid?.takeIf { it.isNotBlank() }?.let { syncQueries.setSubscriptionGuid(it, user.id, wanted.feed_url) }
            }
            entryOf(user, wanted.feed_url)
        }
        return OpaAdded(successes, failures)
    }

    suspend fun find(user: UserAccount, guid: String): OpaLookup {
        val all = database.query {
            syncQueries.allSubscriptionEntries(user.id).executeAsList().map { entry(it.feed_url, it.subscribed, it.changed_at, it.guid, it.deleted_at) }
        }
        val matching = all.filter { it.guid == guid }
        return matching.firstOrNull { it.deleted == null }?.let { OpaLookup.Found(it) }
            ?: if (matching.isNotEmpty()) OpaLookup.Deleted else OpaLookup.Unknown
    }

    /** Applies what is given; the answer tells what changed. Call with an entry [find] found. */
    suspend fun update(user: UserAccount, current: OpaSubscription, change: OpaUpdate): OpaUpdated {
        var feedUrl = current.feed_url
        var updated = OpaUpdated()
        change.new_feed_url?.trim()?.takeIf { it != feedUrl }?.let { newUrl ->
            val added = add(user, listOf(OpaNewSubscription(newUrl, current.guid)))
            if (added.success.isEmpty()) throw IllegalArgumentException(added.failure.firstOrNull()?.message ?: "Feed refused")
            unsubscribe(user, feedUrl)
            database.query { syncQueries.markSubscriptionDeleted(user.id, feedUrl) }
            feedUrl = newUrl
            updated = updated.copy(new_feed_url = newUrl, subscription_changed = added.success.single().subscription_changed)
        }
        change.is_subscribed?.takeIf { it != current.is_subscribed || change.new_feed_url != null }?.let { subscribed ->
            if (subscribed) add(user, listOf(OpaNewSubscription(feedUrl, current.guid))) else unsubscribe(user, feedUrl)
            updated = updated.copy(is_subscribed = subscribed, subscription_changed = entryOf(user, feedUrl)?.subscription_changed)
        }
        change.new_guid?.takeIf { it.isNotBlank() && it != current.guid }?.let { guid ->
            database.query { syncQueries.setSubscriptionGuid(guid, user.id, feedUrl) }
            updated = updated.copy(new_guid = guid, guid_changed = OffsetDateTime.now().iso())
        }
        return updated
    }

    /** Unsubscribes and marks the entry deleted, at once: the status is already SUCCESS. */
    suspend fun delete(user: UserAccount, entry: OpaSubscription): OpaDeletion {
        unsubscribe(user, entry.feed_url)
        val id = database.transaction {
            syncQueries.markSubscriptionDeleted(user.id, entry.feed_url)
            syncQueries.insertDeletion(user.id, entry.feed_url).executeAsOne()
        }
        return OpaDeletion(id, "Deletion request was received and will be processed")
    }

    suspend fun deletion(user: UserAccount, id: Long): OpaDeletionStatus? =
        database.query { syncQueries.deletionOfUser(id, user.id).executeAsOneOrNull() }
            ?.let { OpaDeletionStatus(it.id, "SUCCESS", "Subscription deleted successfully") }

    private suspend fun unsubscribe(user: UserAccount, feedUrl: String) {
        val key = normalizedFeed(feedUrl)
        val subscription = database.query { syncQueries.subscribedPodcasts(user.id).executeAsList() }.firstOrNull { normalizedFeed(it.feed_url) == key }
        if (subscription == null) {
            database.query { syncQueries.recordSubscriptionChange(user.id, feedUrl, false) }
            return
        }
        val orphan = UserCatalog(database, user.id).unsubscribe(subscription.subscription_id.toString())
        if (orphan != null && orphan > 0) removePodcast(orphan)
    }

    private suspend fun entryOf(user: UserAccount, feedUrl: String): OpaSubscription? = database.query {
        syncQueries.allSubscriptionEntries(user.id).executeAsList().firstOrNull { it.feed_url == feedUrl }
            ?.let { entry(it.feed_url, it.subscribed, it.changed_at, it.guid, it.deleted_at) }
    }

    private fun entry(feedUrl: String, subscribed: Boolean, changedAt: OffsetDateTime, guid: String?, deletedAt: OffsetDateTime?) = OpaSubscription(
        feed_url = feedUrl,
        guid = guid ?: podcastGuid(feedUrl),
        is_subscribed = subscribed,
        subscription_changed = changedAt.iso(),
        deleted = deletedAt?.iso(),
    )

    private fun OffsetDateTime.iso(): String = toInstant().truncatedTo(ChronoUnit.MILLIS).toString()
}
