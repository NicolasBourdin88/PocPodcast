package fr.podcast.player

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Owns the ExoPlayer, so that playback goes on in the background: Media3 shows the media
 * notification, the lock screen controls and handles headset buttons and audio focus.
 * The app talks to it through a MediaController ([AndroidAudioPlayer]).
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        // The server's link answers 302 to the publisher's file, often from http (dev) to https.
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(USER_AGENT)
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // pause when the headphones are unplugged
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs((PlaybackController.SKIP_BACK_SECONDS * 1000).toLong())
            .setSeekForwardIncrementMs((PlaybackController.SKIP_FORWARD_SECONDS * 1000).toLong())
            .build()
        val builder = MediaSession.Builder(this, player)
        // Tapping the notification opens the app.
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            builder.setSessionActivity(PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        session = builder.build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiping the app away stops the service, unless something is playing. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private companion object {
        const val USER_AGENT = "Podcast/0.5 (Android; master project)"
    }
}
