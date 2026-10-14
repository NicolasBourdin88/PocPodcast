package fr.podcast.player

import kotlinx.coroutines.flow.StateFlow

/**
 * Plays one audio file. Implemented on each platform (ADR 0008): Media3 on Android, AVPlayer on
 * iOS, JavaFX Media on the JVM (desktop app, CLI), and simulated for the demo and the tests.
 * Methods can be called from any thread; implementations move the work where their engine needs it.
 */
interface AudioPlayer {
    val state: StateFlow<PlayerState>

    /** Replaces the current file with [media], from [startSeconds]; starts at once when [play]. */
    fun load(media: Media, startSeconds: Double, play: Boolean)
    fun play()
    fun pause()
    fun seekTo(seconds: Double)
    fun setSpeed(speed: Float)

    /** Unloads the file. */
    fun stop()

    /** Frees the engine; the player cannot be used any more. */
    fun release()
}

/** What to play, and how the system shows it (lock screen, notification, Now Playing). */
data class Media(
    val id: String,
    val url: String,
    val title: String,
    val artist: String,
    val artworkUrl: String? = null,
    /** Duration announced by the feed, until the player knows the real one. */
    val durationHint: Double? = null,
)

enum class PlayerStatus {
    IDLE,

    /** Opening the file. */
    LOADING,

    /** Playing or paused. */
    READY,
    ENDED,
    ERROR,
}

data class PlayerState(
    val mediaId: String? = null,
    val status: PlayerStatus = PlayerStatus.IDLE,
    /** Playing, or about to (loading or buffering with the intent to play). */
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionSeconds: Double = 0.0,
    /** Null while unknown. */
    val durationSeconds: Double? = null,
    val speed: Float = 1f,
    val error: String? = null,
)
