package fr.podcast.shared.model

import fr.podcast.shared.home.EpisodeItem
import kotlinx.datetime.LocalDate

/**
 * What to look for among the episodes of the user's subscriptions (ADR 0012). Every criterion is
 * optional; they add up. [text]: each word must appear in the title, the description or the
 * podcast's title.
 */
data class EpisodeQuery(
    val text: String = "",
    val status: EpisodeStatus? = null,
    val favoritesOnly: Boolean = false,
    /** 0: any rating. */
    val minRating: Int = 0,
    /** Published from that day on, and until that day, both included. */
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val podcastId: String? = null,
    val tag: String? = null,
) {
    val isEmpty: Boolean
        get() = text.isBlank() && status == null && !favoritesOnly && minRating == 0 && from == null && to == null && podcastId == null && tag == null
}

/** A page of a search, and how many episodes match in all. */
data class EpisodePage(val items: List<EpisodeItem>, val total: Int)
