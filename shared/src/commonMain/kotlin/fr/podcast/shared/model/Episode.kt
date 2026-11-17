package fr.podcast.shared.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.time.Instant

@Serializable
enum class EpisodeStatus { NEW, IN_PROGRESS, PLAYED }

/** The audio file kept by the server (shared by every user, ADR 0007). */
@Serializable
enum class FileStatus { NONE, PENDING, DOWNLOADING, READY, FAILED }

/** A transcript announced by the feed (Podcast 2.0 `podcast:transcript`). */
@Serializable
data class Transcript(val url: String, val type: String, val language: String? = null)

/**
 * An episode as seen by one user: the shared catalog data (title, duration…)
 * plus that user's listening state (position, status) and marks (kept, favorite, rating).
 */
@Serializable
data class Episode(
    val id: String,
    val podcastId: String,
    val title: String,
    val publishedAt: Instant,
    /** 0 when the feed does not give it. */
    val durationSeconds: Int,
    val positionSeconds: Int = 0,
    val status: EpisodeStatus = EpisodeStatus.NEW,
    val description: String = "",
    /** Marked by the user to be kept: its server file is never purged. */
    val kept: Boolean = false,
    /** Among the user's favorites (dynamic playlists can select them, ADR 0010). */
    val favorite: Boolean = false,
    /** The user's rating, from 1 to 5; null when not rated. */
    val rating: Int? = null,
    val fileStatus: FileStatus = FileStatus.NONE,
    /** In the user's queue ("up next"). */
    val inQueue: Boolean = false,
    /** Address a player can open without credentials (signed, it expires); filled by the SDK. */
    @Transient val audioUrl: String? = null,
    /** Podcast 2.0 chapters; sent as a link by the API, filled by the SDK. */
    @Transient val chaptersUrl: String? = null,
    /** Podcast 2.0 transcripts; sent as links by the API, filled by the SDK. */
    @Transient val transcripts: List<Transcript> = emptyList(),
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
