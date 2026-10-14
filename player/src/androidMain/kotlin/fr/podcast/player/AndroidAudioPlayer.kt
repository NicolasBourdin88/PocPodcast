package fr.podcast.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executor

/**
 * Media3 on Android (ADR 0008): commands go to [PlaybackService] through a MediaController,
 * connected on first use. Everything runs on the main thread, as Media3 requires.
 */
class AndroidAudioPlayer(context: Context) : AudioPlayer {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = mutableState.asStateFlow()

    private var controller: MediaController? = null
    private var connecting = false
    private val waiting = mutableListOf<(MediaController) -> Unit>()
    private var mediaId: String? = null
    private var durationHint: Double? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish()
    }

    /** Media3 does not report the position as it moves: read it twice a second while playing. */
    private val ticker = object : Runnable {
        override fun run() = publish()
    }

    override fun load(media: Media, startSeconds: Double, play: Boolean) {
        mutableState.update {
            PlayerState(media.id, PlayerStatus.LOADING, play, positionSeconds = startSeconds, durationSeconds = media.durationHint, speed = it.speed)
        }
        withController { controller ->
            mediaId = media.id
            durationHint = media.durationHint
            val metadata = MediaMetadata.Builder()
                .setTitle(media.title)
                .setArtist(media.artist)
                .apply { media.artworkUrl?.let { setArtworkUri(Uri.parse(it)) } }
                .build()
            val item = MediaItem.Builder().setMediaId(media.id).setUri(media.url).setMediaMetadata(metadata).build()
            controller.setMediaItem(item, (startSeconds * 1000).toLong())
            controller.prepare()
            controller.playWhenReady = play
        }
    }

    override fun play() = withController { controller ->
        if (controller.playerError != null) controller.prepare()
        controller.play()
    }

    override fun pause() = withController { it.pause() }

    override fun seekTo(seconds: Double) = withController { it.seekTo((seconds * 1000).toLong()) }

    override fun setSpeed(speed: Float) = withController { it.setPlaybackSpeed(speed) }

    override fun stop() = withController { controller ->
        mediaId = null
        controller.stop()
        controller.clearMediaItems()
    }

    override fun release() = onMain {
        main.removeCallbacks(ticker)
        controller?.release()
        controller = null
    }

    private fun withController(action: (MediaController) -> Unit) = onMain {
        val connected = controller
        if (connected != null) action(connected) else {
            waiting += action
            connect()
        }
    }

    private fun connect() {
        if (connecting) return
        connecting = true
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        future.addListener(
            {
                connecting = false
                val connected = try {
                    future.get()
                } catch (failure: Exception) {
                    mutableState.update { it.copy(status = PlayerStatus.ERROR, playing = false, error = failure.message) }
                    return@addListener
                }
                controller = connected
                connected.addListener(listener)
                waiting.forEach { it(connected) }
                waiting.clear()
                publish()
            },
            Executor { main.post(it) },
        )
    }

    private fun publish() {
        val controller = controller ?: return
        val error = controller.playerError
        val playbackState = controller.playbackState
        val status = when {
            error != null -> PlayerStatus.ERROR
            mediaId == null || controller.mediaItemCount == 0 -> PlayerStatus.IDLE
            playbackState == Player.STATE_ENDED -> PlayerStatus.ENDED
            playbackState == Player.STATE_READY -> PlayerStatus.READY
            playbackState == Player.STATE_BUFFERING && controller.duration != C.TIME_UNSET -> PlayerStatus.READY
            else -> PlayerStatus.LOADING
        }
        mutableState.value = PlayerState(
            mediaId = controller.currentMediaItem?.mediaId ?: mediaId,
            status = status,
            playing = controller.playWhenReady && (status == PlayerStatus.READY || status == PlayerStatus.LOADING),
            buffering = playbackState == Player.STATE_BUFFERING,
            positionSeconds = controller.currentPosition / 1000.0,
            durationSeconds = controller.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.let { it / 1000.0 } ?: durationHint,
            speed = controller.playbackParameters.speed,
            error = error?.message,
        )
        main.removeCallbacks(ticker)
        if (controller.isPlaying) main.postDelayed(ticker, TICK_MILLIS)
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private companion object {
        const val TICK_MILLIS = 500L
    }
}
