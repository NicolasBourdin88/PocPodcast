package fr.podcast.shared.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
enum class EpisodeStatus { NEW, IN_PROGRESS, PLAYED }

/**
 * An episode as seen by one user: the shared catalog data (title, duration…)
 * plus that user's listening state (position, status).
 */
@Serializable
data class Episode(
    val id: String,
    val podcastId: String,
    val title: String,
    val publishedAt: Instant,
    val durationSeconds: Int,
    val positionSeconds: Int = 0,
    val status: EpisodeStatus = EpisodeStatus.NEW,
    val description: String = "",
) {
    /** Listening progress between 0 and 1. */
    val progress: Float
        get() = if (durationSeconds <= 0) 0f else (positionSeconds.toFloat() / durationSeconds).coerceIn(0f, 1f)

    val remainingMinutes: Int
        get() = ((durationSeconds - positionSeconds).coerceAtLeast(0) + 59) / 60

    /** Publication day in [timeZone], for display. */
    fun publishedOn(timeZone: TimeZone = TimeZone.currentSystemDefault()): LocalDate =
        publishedAt.toLocalDateTime(timeZone).date
}
