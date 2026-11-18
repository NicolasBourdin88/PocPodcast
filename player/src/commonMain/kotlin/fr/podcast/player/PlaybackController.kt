package fr.podcast.player

import fr.podcast.shared.home.EpisodeItem
import fr.podcast.shared.model.EpisodeStatus
import fr.podcast.shared.repository.PodcastClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The episode being played and the player's state, for every screen. */
data class NowPlaying(val item: EpisodeItem, val state: PlayerState) {
    /** The player's duration once known, else the feed's. */
    val durationSeconds: Double
        get() = state.durationSeconds ?: item.episode.durationSeconds.toDouble()

    val progress: Float
        get() = if (durationSeconds <= 0) 0f else (state.positionSeconds / durationSeconds).toFloat().coerceIn(0f, 1f)
}

/**
 * Playback shared by every client (ADR 0008): it plays episodes of the current account on an
 * [AudioPlayer] and keeps the server informed.
 * - Position: sent every [syncInterval] while playing, and at once on pause, seek or change of
 *   episode. The last write wins; a failed send is retried at the next one.
 * - End: the episode is marked played (the server takes it out of the queue), then the next one
 *   in the queue starts.
 * - Errors: the episode is read again once (a signed link may have expired), then the error stays.
 * - Queue: episodes to play next, read from the server; playing an episode takes it out.
 */
class PlaybackController(
    private val player: AudioPlayer,
    private val scope: CoroutineScope,
    private val syncInterval: Duration = 15.seconds,
) {
    private var client: PodcastClient? = null
    private val current = MutableStateFlow<EpisodeItem?>(null)
    private val mutableQueue = MutableStateFlow<List<EpisodeItem>>(emptyList())
    private val mutableFailures = MutableSharedFlow<Throwable>(extraBufferCapacity = 8)

    /** Server calls and player commands one at a time, in order. */
    private val mutex = Mutex()
    private var lastSaved: Int? = null
    private var retried = false
    private var pendingSave: Job? = null
    private var previous = PlayerState()

    val nowPlaying: StateFlow<NowPlaying?> = combine(current, player.state) { item, state ->
        item?.let { NowPlaying(it, if (state.mediaId == it.episode.id) state else PlayerState(mediaId = it.episode.id)) }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    val queue: StateFlow<List<EpisodeItem>> = mutableQueue.asStateFlow()

    /** Failed server calls and playback errors, for a message on screen. */
    val failures: SharedFlow<Throwable> = mutableFailures.asSharedFlow()

    init {
        scope.launch { player.state.collect(::onPlayerState) }
        scope.launch {
            while (isActive) {
                delay(syncInterval)
                if (player.state.value.playing) mutex.withLock { save() }
            }
        }
    }

    /** Plays the episodes of [client] from now on (null: signed out). What was playing stops. */
    fun attach(client: PodcastClient?) {
        scope.launch {
            mutex.withLock {
                if (client === this@PlaybackController.client) return@withLock
                save()
                player.stop()
                current.value = null
                mutableQueue.value = emptyList()
                this@PlaybackController.client = client
                refreshQueueLocked()
            }
        }
    }

    /** Reads the episode again (position saved elsewhere, fresh audio link) and plays it. */
    fun play(episodeId: String) {
        scope.launch { mutex.withLock { load(episodeId, play = true) } }
    }

    fun togglePlayPause() {
        val state = player.state.value
        when {
            current.value == null -> Unit
            state.playing -> player.pause()
            state.status == PlayerStatus.ENDED -> {
                player.seekTo(0.0)
                player.play()
            }
            state.status == PlayerStatus.ERROR || state.status == PlayerStatus.IDLE -> current.value?.let { play(it.episode.id) }
            else -> player.play()
        }
    }

    fun pause() = player.pause()

    fun seekBy(seconds: Double) {
        val state = player.state.value
        val duration = nowPlaying.value?.durationSeconds?.takeIf { it > 0 } ?: Double.MAX_VALUE
        seekTo((state.positionSeconds + seconds).coerceIn(0.0, duration))
    }

    fun seekTo(seconds: Double) = player.seekTo(seconds.coerceAtLeast(0.0))

    fun setSpeed(speed: Float) = player.setSpeed(speed)

    /** Next speed of [SPEEDS], back to the first after the last. */
    fun cycleSpeed() {
        val speed = player.state.value.speed
        setSpeed(SPEEDS.firstOrNull { it > speed + 0.01f } ?: SPEEDS.first())
    }

    /** Plays the first episode of the queue. */
    fun skipToNext() {
        scope.launch {
            mutex.withLock {
                save()
                queue.value.firstOrNull { it.episode.id != current.value?.episode?.id }?.let { load(it.episode.id, play = true) }
            }
        }
    }

    fun enqueue(episodeId: String, next: Boolean = false) = queueChange { it.enqueue(episodeId, if (next) 0 else null) }

    /**
     * Plays a list (a playlist, ADR 0010): the first episode now, the others first in the queue, in
     * order, before what was already there.
     */
    fun playAll(episodeIds: List<String>) {
        val first = episodeIds.firstOrNull() ?: return
        queueChange { client -> episodeIds.drop(1).forEachIndexed { index, id -> client.enqueue(id, index) } }
        play(first)
    }

    fun dequeue(episodeId: String) = queueChange { it.dequeue(episodeId) }

    fun moveInQueue(episodeId: String, position: Int) = queueChange { it.moveInQueue(episodeId, position) }

    fun clearQueue() = queueChange { it.clearQueue() }

    fun refreshQueue() {
        scope.launch { mutex.withLock { refreshQueueLocked() } }
    }

    /** Sends the position now, e.g. before the app goes to the background. */
    fun saveNow() {
        scope.launch { mutex.withLock { save() } }
    }

    /** Sends the position and waits for the answer, before the app closes. */
    suspend fun flush() = mutex.withLock { save() }

    private fun queueChange(change: suspend (PodcastClient) -> Unit) {
        scope.launch {
            mutex.withLock {
                val client = client ?: return@withLock
                attempt { change(client) }
                refreshQueueLocked()
            }
        }
    }

    private suspend fun refreshQueueLocked() {
        val client = client ?: return
        attempt { client.queue() }?.let { mutableQueue.value = it }
    }

    private suspend fun load(episodeId: String, play: Boolean) {
        val client = client ?: return
        save()
        val item = attempt { client.episodeItem(episodeId) } ?: return
        val url = item.episode.audioUrl ?: return report(IllegalStateException("No audio for episode $episodeId"))
        // A played episode starts again from the beginning.
        val start = if (item.episode.status == EpisodeStatus.PLAYED) 0 else item.episode.positionSeconds
        current.value = item
        lastSaved = start
        retried = false
        player.load(item.toMedia(url), start.toDouble(), play)
        if (item.episode.inQueue) {
            attempt { client.dequeue(episodeId) }
            refreshQueueLocked()
        }
    }

    private fun onPlayerState(state: PlayerState) {
        val before = previous
        previous = state
        val item = current.value ?: return
        if (state.mediaId != item.episode.id) return
        when {
            state.status == PlayerStatus.ENDED && before.status != PlayerStatus.ENDED ->
                scope.launch { mutex.withLock { onEnded(item) } }
            state.status == PlayerStatus.ERROR && before.status != PlayerStatus.ERROR ->
                scope.launch { mutex.withLock { onError(item, state) } }
            before.playing && !state.playing -> saveSoon(immediately = true)
            // A seek while paused.
            !state.playing && abs(state.positionSeconds - before.positionSeconds) >= 1 -> saveSoon(immediately = false)
        }
    }

    /** Pauses send at once; seeks wait a little, as a seek bar sends several in a row. */
    private fun saveSoon(immediately: Boolean) {
        pendingSave?.cancel()
        pendingSave = scope.launch {
            if (!immediately) delay(1.seconds)
            mutex.withLock { save() }
        }
    }

    private suspend fun onEnded(item: EpisodeItem) {
        val client = client ?: return
        lastSaved = null
        attempt { client.markPlayed(item.episode.id) }
        refreshQueueLocked()
        val next = queue.value.firstOrNull { it.episode.id != item.episode.id } ?: return
        load(next.episode.id, play = true)
    }

    private suspend fun onError(item: EpisodeItem, state: PlayerState) {
        if (retried) return report(PlaybackException(state.error))
        retried = true
        val client = client ?: return
        val fresh = attempt { client.episodeItem(item.episode.id) } ?: return
        val url = fresh.episode.audioUrl ?: return
        val position = state.positionSeconds.takeIf { it > 0 } ?: lastSaved?.toDouble() ?: 0.0
        player.load(fresh.toMedia(url), position, play = true)
    }

    /**
     * Sends the current position if it changed. Called with [mutex] held. Only once the file is
     * open (READY): before, the player is at 0, and sending that would erase the saved position.
     */
    private suspend fun save() {
        val item = current.value ?: return
        val client = client ?: return
        val state = player.state.value
        if (state.mediaId != item.episode.id || state.status != PlayerStatus.READY) return
        val position = state.positionSeconds.toInt()
        if (position == lastSaved) return
        if (attempt { client.updateProgress(item.episode.id, position) } != null) lastSaved = position
    }

    private suspend fun <T> attempt(block: suspend () -> T): T? =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            report(failure)
            null
        }

    private fun report(failure: Throwable) {
        mutableFailures.tryEmit(failure)
    }

    companion object {
        val SPEEDS = listOf(0.8f, 1f, 1.2f, 1.5f, 1.8f, 2f)
        const val SKIP_BACK_SECONDS = 15.0
        const val SKIP_FORWARD_SECONDS = 30.0
    }
}

/** The player could not play the file (format, network…). */
class PlaybackException(message: String?) : Exception(message)

private fun EpisodeItem.toMedia(url: String) = Media(
    id = episode.id,
    url = url,
    title = episode.title,
    artist = podcast.title,
    artworkUrl = podcast.artworkUrl,
    durationHint = episode.durationSeconds.takeIf { it > 0 }?.toDouble(),
)
