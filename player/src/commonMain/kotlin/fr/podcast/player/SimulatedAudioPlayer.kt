package fr.podcast.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A player without sound for the demo mode: the position moves as if the episode were playing,
 * using the duration announced by the feed. Nothing is downloaded.
 */
class SimulatedAudioPlayer(private val scope: CoroutineScope, private val tickMillis: Long = 250) : AudioPlayer {
    private val mutableState = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = mutableState.asStateFlow()
    private var clock: Job? = null

    override fun load(media: Media, startSeconds: Double, play: Boolean) {
        val duration = media.durationHint ?: DEFAULT_DURATION
        mutableState.update {
            PlayerState(media.id, PlayerStatus.READY, play, positionSeconds = startSeconds.coerceIn(0.0, duration), durationSeconds = duration, speed = it.speed)
        }
        if (play) startClock()
    }

    override fun play() {
        if (mutableState.value.status != PlayerStatus.READY) return
        mutableState.update { it.copy(playing = true) }
        startClock()
    }

    override fun pause() {
        clock?.cancel()
        mutableState.update { it.copy(playing = false) }
    }

    override fun seekTo(seconds: Double) {
        mutableState.update { it.copy(positionSeconds = seconds.coerceIn(0.0, it.durationSeconds ?: seconds)) }
    }

    override fun setSpeed(speed: Float) {
        mutableState.update { it.copy(speed = speed) }
    }

    override fun stop() {
        clock?.cancel()
        mutableState.update { PlayerState(speed = it.speed) }
    }

    override fun release() = stop()

    private fun startClock() {
        clock?.cancel()
        clock = scope.launch {
            while (isActive) {
                delay(tickMillis)
                val state = mutableState.value
                if (!state.playing) break
                val duration = state.durationSeconds ?: DEFAULT_DURATION
                val position = state.positionSeconds + tickMillis / 1000.0 * state.speed
                if (position >= duration) {
                    mutableState.value = state.copy(status = PlayerStatus.ENDED, playing = false, positionSeconds = duration)
                    break
                }
                mutableState.value = state.copy(positionSeconds = position)
            }
        }
    }

    private companion object {
        const val DEFAULT_DURATION = 30 * 60.0
    }
}
