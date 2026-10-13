package fr.podcast.shared.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

@Serializable
enum class EpisodeStatus { NEW, IN_PROGRESS, PLAYED }

@Serializable
data class Episode(
    val id: String,
    val podcastId: String,
    val title: String,
    val publishedOn: LocalDate,
    val durationSeconds: Int,
    val positionSeconds: Int = 0,
    val status: EpisodeStatus = EpisodeStatus.NEW,
) {
    /** Listening progress between 0 and 1. */
    val progress: Float
        get() = if (durationSeconds <= 0) 0f else (positionSeconds.toFloat() / durationSeconds).coerceIn(0f, 1f)

    val remainingMinutes: Int
        get() = ((durationSeconds - positionSeconds).coerceAtLeast(0) + 59) / 60
}
