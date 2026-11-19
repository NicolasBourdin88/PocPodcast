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
    /** The owner's labels ("trajets", "tech"…), which group the library and filter searches (ADR 0012). */
    val tags: List<String> = emptyList(),
)

data class SubscriptionItem(val subscription: Subscription, val podcast: Podcast) {
    /**
     * The library filters (ADR 0012), the same on the server and in the apps: every word of [text]
     * in the title, the author, the description or the note, and [tag] (if any) among the tags.
     */
    fun matches(text: String, tag: String?): Boolean {
        val haystack = listOf(podcast.title, podcast.author, podcast.description, subscription.note.orEmpty()).joinToString(" ").lowercase()
        val words = text.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return words.all { it in haystack } && (tag.isNullOrEmpty() || subscription.tags.any { it.equals(tag, ignoreCase = true) })
    }
}

/** The tags of a subscription (ADR 0012): checked by the server, and by clients before sending. */
object TagRules {
    const val MAX_TAGS = 10
    const val MAX_LENGTH = 30

    /** "trajets, Tech , tech" → [trajets, Tech]: trimmed, without empty ones nor duplicates differing by case. */
    fun parse(text: String): List<String> = normalize(text.split(','))

    fun normalize(tags: List<String>): List<String> =
        tags.map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }

    fun valid(tags: List<String>): Boolean =
        tags.size <= MAX_TAGS && tags.all { it.isNotBlank() && it.length <= MAX_LENGTH && ',' !in it }

    /** Alphabetical, case and accents aside ("dev", "économie", "english"), without duplicates differing by case. */
    fun sorted(tags: Collection<String>): List<String> =
        tags.distinctBy { it.lowercase() }.sortedWith(compareBy({ sortKey(it) }, { it }))

    private const val ACCENTED = "àâäáãåçéèêëîïíìñôöóòõùûüúÿý"
    private const val PLAIN = "aaaaaaceeeeiiiinooooouuuuyy"

    private fun sortKey(tag: String): String = buildString {
        tag.lowercase().forEach { char ->
            when (char) {
                'œ' -> append("oe")
                'æ' -> append("ae")
                else -> append(ACCENTED.indexOf(char).takeIf { it >= 0 }?.let(PLAIN::get) ?: char)
            }
        }
    }
}

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
