package fr.podcast.shared.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * The user's listening (ADR 0012), in episode time: 10 minutes at 1.5× count as 10 minutes of the
 * episode. [since] is the first day recorded, null when nothing was listened to yet.
 */
@Serializable
data class ListeningStats(
    val totalSeconds: Long,
    val last7DaysSeconds: Long,
    val last30DaysSeconds: Long,
    val episodesFinished: Int,
    val since: LocalDate? = null,
    /** The most listened to first. */
    val byPodcast: List<PodcastListening> = emptyList(),
    /** The last twelve months, oldest first, months without listening included. */
    val byMonth: List<MonthListening> = emptyList(),
)

@Serializable
data class PodcastListening(val podcastTitle: String, val feedUrl: String, val seconds: Long, val episodesFinished: Int)

/** [month] as "2026-11". */
@Serializable
data class MonthListening(val month: String, val seconds: Long)
