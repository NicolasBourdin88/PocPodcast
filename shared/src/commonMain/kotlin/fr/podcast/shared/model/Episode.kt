package fr.podcast.shared.model

import kotlinx.serialization.Serializable

@Serializable
enum class EpisodeStatus { NEW, IN_PROGRESS, PLAYED }

@Serializable
data class Episode(
    val id: String,
    val podcastId: String,
    val title: String,
    /** Human-readable publication date, e.g. "12 oct.". Will become a real date in step 3. */
    val publishedLabel: String,
    val durationSeconds: Int,
    val positionSeconds: Int = 0,
    val status: EpisodeStatus = EpisodeStatus.NEW,
) {
    /** Listening progress between 0 and 1. */
    val progress: Float
        get() = if (durationSeconds <= 0) 0f else (positionSeconds.toFloat() / durationSeconds).coerceIn(0f, 1f)

    val remainingMinutes: Int
        get() = ((durationSeconds - positionSeconds).coerceAtLeast(0) + 59) / 60

    /** Duration formatted as "1 h 05" or "42 min". */
    val durationLabel: String
        get() {
            val totalMinutes = durationSeconds / 60
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            return if (hours > 0) "$hours h ${minutes.toString().padStart(2, '0')}" else "$minutes min"
        }
}
