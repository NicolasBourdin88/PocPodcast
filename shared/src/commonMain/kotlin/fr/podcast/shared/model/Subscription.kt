package fr.podcast.shared.model

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** What the viewer may do with a resource (ADR 0009): everything, modify it, or only read it. */
@Serializable
enum class Access { OWNER, WRITE, READ }

/** A user's subscription: their own entry pointing to a shared podcast. */
@Serializable
data class Subscription(
    val id: String,
    /** The user's annotation. */
    val note: String? = null,
    val subscribedAt: Instant,
    /** The server downloads new episodes of this podcast for this user. */
    val autoDownload: Boolean = false,
    /** The viewer's rights: OWNER for their own subscriptions, READ or WRITE when shared with them. */
    val access: Access = Access.OWNER,
    /** Who shared it, when it is not the viewer's own. */
    val ownerName: String? = null,
)

data class SubscriptionItem(val subscription: Subscription, val podcast: Podcast)

/** A podcast found by a search source (Podcast Index, Apple Podcasts…). */
@Serializable
data class SearchResult(
    val title: String,
    val author: String,
    val feedUrl: String,
    val artworkUrl: String? = null,
    val description: String = "",
    val source: String,
    val subscribed: Boolean = false,
)

@Serializable
data class OpmlFailure(val feedUrl: String, val reason: String)

/** Outcome of an OPML import. */
@Serializable
data class OpmlImportReport(
    val imported: Int,
    val alreadySubscribed: Int,
    val failed: List<OpmlFailure> = emptyList(),
)
