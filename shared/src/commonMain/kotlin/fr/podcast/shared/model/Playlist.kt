package fr.podcast.shared.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.time.Instant

/** STATIC: episodes in the order chosen by the user; DYNAMIC: computed from [PlaylistRules] (ADR 0010). */
@Serializable
enum class PlaylistKind { STATIC, DYNAMIC }

/** Order of a dynamic playlist; RANDOM stays the same until the playlist is shuffled again. */
@Serializable
enum class PlaylistOrder { NEWEST, OLDEST, RANDOM }

/**
 * What a dynamic playlist selects among its owner's subscriptions, with the owner's marks
 * (favorites, ratings, statuses), whoever looks at it (ADR 0010).
 */
@Serializable
data class PlaylistRules(
    /** An episode matches when its title, description or podcast contains one of them (any case); empty: every episode. */
    val keywords: List<String> = emptyList(),
    val favoritesOnly: Boolean = false,
    /** From 1 to 5; 0: any rating, even none. */
    val minRating: Int = 0,
    /** Leaves played episodes out. */
    val unplayedOnly: Boolean = false,
    val order: PlaylistOrder = PlaylistOrder.NEWEST,
    /** At most this many episodes, from 1 to [MAX_LIMIT]. */
    val limit: Int = DEFAULT_LIMIT,
) {
    companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 100
        const val MAX_KEYWORDS = 10
        const val MAX_KEYWORD_LENGTH = 50

        /** "kotlin, Java ,,ktor" → [kotlin, Java, ktor]: keywords are separated by commas. */
        fun parseKeywords(text: String): List<String> =
            text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
    }
}

/** A playlist as one user sees it (ADR 0010). */
@Serializable
data class Playlist(
    val id: String,
    val title: String,
    val description: String? = null,
    val kind: PlaylistKind,
    /** For a dynamic playlist; null for a static one. */
    val rules: PlaylistRules? = null,
    val episodeCount: Int = 0,
    /** The viewer's rights: OWNER, or READ/WRITE when it is shared with them. */
    val access: Access = Access.OWNER,
    /** Who shared it, when it is not the viewer's own. */
    val ownerName: String? = null,
    val createdAt: Instant,
    /** Address of its RSS feed once the owner published it (only the owner sees it); sent as a link, filled by the SDK. */
    @Transient val feedUrl: String? = null,
)

/** Rules on playlists, checked by the server and by the clients before they send. */
object PlaylistLimits {
    const val MAX_TITLE_LENGTH = 100
    const val MAX_DESCRIPTION_LENGTH = 500

    fun validTitle(title: String): Boolean = title.trim().length in 1..MAX_TITLE_LENGTH

    /** The fields that break a rule, by their API name; empty when every rule holds. */
    fun invalidFields(rules: PlaylistRules): List<String> = buildList {
        if (rules.keywords.size > PlaylistRules.MAX_KEYWORDS || rules.keywords.any { it.length > PlaylistRules.MAX_KEYWORD_LENGTH }) add("keywords")
        if (rules.minRating !in 0..5) add("minRating")
        if (rules.limit !in 1..PlaylistRules.MAX_LIMIT) add("limit")
    }
}
