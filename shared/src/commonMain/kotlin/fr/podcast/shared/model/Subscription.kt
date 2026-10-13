package fr.podcast.shared.model

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** A user's subscription: their own entry pointing to a shared podcast. */
@Serializable
data class Subscription(
    val id: String,
    /** The user's annotation. */
    val note: String? = null,
    val subscribedAt: Instant,
    /** The server downloads new episodes of this podcast for this user. */
    val autoDownload: Boolean = false,
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
