package fr.podcast.player

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import platform.AVFoundation.AVAudioTimePitchAlgorithmTimeDomain
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVPlayerTimeControlStatusPaused
import platform.AVFoundation.AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.pause
import platform.AVFoundation.rate
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.setAudioTimePitchAlgorithm
import platform.AVFoundation.setRate
import platform.AVFoundation.timeControlStatus
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSDate
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSThread
import platform.Foundation.NSTimer
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970
import platform.MediaPlayer.MPChangePlaybackPositionCommandEvent
import platform.MediaPlayer.MPMediaItemPropertyArtist
import platform.MediaPlayer.MPMediaItemPropertyPlaybackDuration
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoPropertyDefaultPlaybackRate
import platform.MediaPlayer.MPNowPlayingInfoPropertyElapsedPlaybackTime
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackRate
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess
import platform.darwin.NSObjectProtocol
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.math.abs

/**
 * AVPlayer on iOS and macOS (ADR 0008, 0013). On iOS the audio session plays in the background (the
 * app declares the "audio" background mode); Now Playing feeds the lock screen, Control Center and
 * the Mac's menu bar, and the remote commands (headphones, media keys) act on this player.
 * Everything runs on the main thread.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleAudioPlayer : AudioPlayer {
    private val player = AVPlayer()
    private val mutableState = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = mutableState.asStateFlow()

    private var media: Media? = null
    private var speed = 1f
    private var wantsToPlay = false
    private var ended = false
    private var endObserver: NSObjectProtocol? = null
    private var timer: NSTimer? = null

    // What Now Playing was last told: the system extrapolates the position from it.
    private var infoPosition = 0.0
    private var infoTime = 0.0
    private var infoKey: Any? = null

    init {
        onMain {
            configureAudioSession()
            // AVPlayer does not report every change: read its state twice a second.
            timer = NSTimer.scheduledTimerWithTimeInterval(0.5, repeats = true) { _ -> publish() }
            remoteCommands()
        }
    }

    override fun load(media: Media, startSeconds: Double, play: Boolean) = onMain {
        val url = NSURL.URLWithString(media.url) ?: return@onMain fail("Invalid address")
        this.media = media
        ended = false
        wantsToPlay = play
        activateAudioSession()
        val item = AVPlayerItem(uRL = url)
        item.setAudioTimePitchAlgorithm(AVAudioTimePitchAlgorithmTimeDomain) // suited to speech at 1.5×
        endObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        endObserver = NSNotificationCenter.defaultCenter.addObserverForName(AVPlayerItemDidPlayToEndTimeNotification, item, NSOperationQueue.mainQueue) { _ ->
            ended = true
            wantsToPlay = false
            publish()
        }
        player.replaceCurrentItemWithPlayerItem(item)
        if (startSeconds > 0) player.seekToTime(CMTimeMakeWithSeconds(startSeconds, TIMESCALE))
        if (play) player.setRate(speed)
        publish()
    }

    override fun play() = onMain {
        if (player.currentItem == null) return@onMain
        if (ended) {
            ended = false
            player.seekToTime(CMTimeMakeWithSeconds(0.0, TIMESCALE))
        }
        wantsToPlay = true
        player.setRate(speed)
        publish()
    }

    override fun pause() = onMain {
        wantsToPlay = false
        player.pause()
        publish()
    }

    override fun seekTo(seconds: Double) = onMain {
        ended = false
        player.seekToTime(CMTimeMakeWithSeconds(seconds, TIMESCALE))
        publish()
    }

    override fun setSpeed(speed: Float) = onMain {
        this.speed = speed
        if (player.rate > 0f) player.setRate(speed)
        publish()
    }

    override fun stop() = onMain {
        wantsToPlay = false
        media = null
        player.pause()
        player.replaceCurrentItemWithPlayerItem(null)
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = null
        publish()
    }

    override fun release() = onMain {
        stop()
        timer?.invalidate()
        timer = null
        endObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
    }

    private fun publish() {
        val media = media
        val item = player.currentItem
        if (media == null || item == null) {
            mutableState.value = PlayerState(speed = speed)
            return
        }
        val status = when {
            item.status == AVPlayerItemStatusFailed -> PlayerStatus.ERROR
            ended -> PlayerStatus.ENDED
            item.status == AVPlayerItemStatusReadyToPlay -> PlayerStatus.READY
            else -> PlayerStatus.LOADING
        }
        val position = CMTimeGetSeconds(player.currentTime()).takeIf { it.isFinite() } ?: 0.0
        val duration = CMTimeGetSeconds(item.duration).takeIf { it.isFinite() && it > 0 } ?: media.durationHint
        val playing = wantsToPlay && (status == PlayerStatus.READY || status == PlayerStatus.LOADING)
        val state = PlayerState(
            mediaId = media.id,
            status = status,
            playing = playing,
            buffering = player.timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate,
            positionSeconds = position,
            durationSeconds = duration,
            speed = speed,
            error = item.error?.localizedDescription,
        )
        mutableState.value = state
        if (player.timeControlStatus == AVPlayerTimeControlStatusPaused && wantsToPlay && status == PlayerStatus.READY) {
            // Stalled earlier (network): try again.
            player.setRate(speed)
        }
        nowPlaying(media, state)
    }

    /** Updates Now Playing when something changes that the system cannot extrapolate. */
    private fun nowPlaying(media: Media, state: PlayerState) {
        val now = NSDate().timeIntervalSince1970
        val rate = if (state.playing && !state.buffering) state.speed.toDouble() else 0.0
        val expected = infoPosition + (now - infoTime) * rate
        val key = listOf(media.id, rate, state.durationSeconds, state.status)
        if (key == infoKey && abs(state.positionSeconds - expected) < 2) return
        infoKey = key
        infoPosition = state.positionSeconds
        infoTime = now
        val info = mutableMapOf<Any?, Any?>(
            MPMediaItemPropertyTitle to media.title,
            MPMediaItemPropertyArtist to media.artist,
            MPNowPlayingInfoPropertyElapsedPlaybackTime to state.positionSeconds,
            MPNowPlayingInfoPropertyPlaybackRate to rate,
            MPNowPlayingInfoPropertyDefaultPlaybackRate to state.speed.toDouble(),
        )
        state.durationSeconds?.let { info[MPMediaItemPropertyPlaybackDuration] = it }
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = info
    }

    /** Lock screen, Control Center and headphones. */
    private fun remoteCommands() {
        val center = MPRemoteCommandCenter.sharedCommandCenter()
        center.playCommand.addTargetWithHandler { _ ->
            play()
            MPRemoteCommandHandlerStatusSuccess
        }
        center.pauseCommand.addTargetWithHandler { _ ->
            pause()
            MPRemoteCommandHandlerStatusSuccess
        }
        center.togglePlayPauseCommand.addTargetWithHandler { _ ->
            if (wantsToPlay) pause() else play()
            MPRemoteCommandHandlerStatusSuccess
        }
        center.skipForwardCommand.preferredIntervals = listOf(PlaybackController.SKIP_FORWARD_SECONDS)
        center.skipForwardCommand.addTargetWithHandler { _ ->
            seekTo(mutableState.value.positionSeconds + PlaybackController.SKIP_FORWARD_SECONDS)
            MPRemoteCommandHandlerStatusSuccess
        }
        center.skipBackwardCommand.preferredIntervals = listOf(PlaybackController.SKIP_BACK_SECONDS)
        center.skipBackwardCommand.addTargetWithHandler { _ ->
            seekTo((mutableState.value.positionSeconds - PlaybackController.SKIP_BACK_SECONDS).coerceAtLeast(0.0))
            MPRemoteCommandHandlerStatusSuccess
        }
        center.changePlaybackPositionCommand.addTargetWithHandler { event ->
            (event as? MPChangePlaybackPositionCommandEvent)?.let { seekTo(it.positionTime) }
            MPRemoteCommandHandlerStatusSuccess
        }
    }

    private fun fail(message: String) {
        mutableState.update { it.copy(status = PlayerStatus.ERROR, playing = false, error = message) }
    }

    private fun onMain(block: () -> Unit) {
        if (NSThread.isMainThread) block() else dispatch_async(dispatch_get_main_queue(), block)
    }

    private companion object {
        const val TIMESCALE = 600
    }
}

/** iOS: spoken audio that keeps playing in the background; nothing to do on the Mac. */
internal expect fun configureAudioSession()

internal expect fun activateAudioSession()
