package fr.podcast.player

import javafx.application.Platform
import javafx.scene.media.MediaPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import javafx.scene.media.Media as FxMedia
import javafx.util.Duration as FxDuration

/**
 * JavaFX Media, for the desktop app and the CLI (ADR 0008): MP3 and AAC, not Opus.
 * Two lessons from the POC (`poc/player`):
 * - JavaFX downloads through java.net, whose default User-Agent ("Java/21…") some CDNs refuse:
 *   the app's own is set instead;
 * - java.net does not follow a redirect from http to https: the redirects are resolved first,
 *   and JavaFX gets the final address.
 * The JavaFX toolkit starts with the first episode loaded (not with the app, which opens faster);
 * [release] stops it, so that the JVM can exit.
 */
class JavaFxAudioPlayer(private val userAgent: String = USER_AGENT) : AudioPlayer {
    private val mutableState = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = mutableState.asStateFlow()

    private val resolver = Executors.newSingleThreadExecutor { Thread(it, "audio-redirects").apply { isDaemon = true } }
    private val http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    /** Used on the JavaFX thread only. */
    private var player: MediaPlayer? = null

    /** Each load gets a number; the answers of an older one are ignored. */
    @Volatile
    private var generation = 0

    init {
        if (System.getProperty("http.agent") == null) System.setProperty("http.agent", userAgent)
    }

    override fun load(media: Media, startSeconds: Double, play: Boolean) {
        val load = ++generation
        mutableState.update {
            PlayerState(media.id, PlayerStatus.LOADING, play, positionSeconds = startSeconds, durationSeconds = media.durationHint, speed = it.speed)
        }
        onFx { dispose() }
        resolver.execute {
            startToolkit()
            val url = try {
                resolve(media.url)
            } catch (failure: Exception) {
                if (load == generation) fail(failure.message ?: failure.toString())
                return@execute
            }
            onFx { if (load == generation) open(url) }
        }
    }

    override fun play() {
        mutableState.update { it.copy(playing = true, status = if (it.status == PlayerStatus.ENDED) PlayerStatus.READY else it.status) }
        onFx { player?.play() }
    }

    override fun pause() {
        mutableState.update { it.copy(playing = false) }
        onFx { player?.pause() }
    }

    override fun seekTo(seconds: Double) {
        mutableState.update { it.copy(positionSeconds = seconds, status = if (it.status == PlayerStatus.ENDED) PlayerStatus.READY else it.status) }
        onFx { player?.seek(FxDuration.seconds(seconds)) }
    }

    override fun setSpeed(speed: Float) {
        mutableState.update { it.copy(speed = speed) }
        onFx { player?.rate = speed.toDouble() }
    }

    override fun stop() {
        generation++
        mutableState.update { PlayerState(speed = it.speed) }
        onFx { dispose() }
    }

    override fun release() {
        stop()
        resolver.shutdownNow()
        if (toolkitStarted) Platform.exit()
    }

    /** Opens [url] on the JavaFX thread; the position and intent to play are read from the state. */
    private fun open(url: String) {
        val fx = try {
            MediaPlayer(FxMedia(url))
        } catch (failure: Exception) {
            return fail(failure.message ?: failure.toString())
        }
        player = fx
        fx.rate = mutableState.value.speed.toDouble()
        fx.setOnReady {
            val start = mutableState.value.positionSeconds
            if (start > 0) fx.seek(FxDuration.seconds(start))
            val duration = fx.totalDuration.toSeconds().takeIf { it.isFinite() && it > 0 }
            mutableState.update { it.copy(status = PlayerStatus.READY, durationSeconds = duration ?: it.durationSeconds) }
            if (mutableState.value.playing) fx.play()
        }
        fx.setOnPlaying { mutableState.update { it.copy(playing = true, buffering = false) } }
        fx.setOnPaused { mutableState.update { it.copy(playing = false) } }
        fx.setOnStalled { mutableState.update { it.copy(buffering = true) } }
        fx.setOnEndOfMedia {
            mutableState.update { it.copy(status = PlayerStatus.ENDED, playing = false, positionSeconds = it.durationSeconds ?: it.positionSeconds) }
        }
        fx.setOnError { fail(fx.error?.message ?: "Media error") }
        fx.currentTimeProperty().addListener { _, _, time ->
            val seconds = time.toSeconds()
            // A few updates per second are enough for a progress bar.
            if (abs(seconds - mutableState.value.positionSeconds) >= 0.25) mutableState.update { it.copy(positionSeconds = seconds, buffering = false) }
        }
    }

    private fun dispose() {
        player?.dispose()
        player = null
    }

    private fun fail(message: String) {
        mutableState.update { it.copy(status = PlayerStatus.ERROR, playing = false, buffering = false, error = message) }
    }

    /** Final address after every redirect (the server's link answers 302 when it has no copy). */
    private fun resolve(url: String): String {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return url
        val request = HttpRequest.newBuilder(URI(url))
            .header("Range", "bytes=0-0")
            .header("User-Agent", userAgent)
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.discarding())
        if (response.statusCode() >= 400) throw IOException("HTTP ${response.statusCode()}")
        return response.uri().toString()
    }

    /** Before the first load there is no JavaFX object to act on. */
    private fun onFx(block: () -> Unit) {
        if (!toolkitStarted) return
        if (Platform.isFxApplicationThread()) block() else Platform.runLater(block)
    }

    companion object {
        const val USER_AGENT = "Podcast/0.5 (master project)"

        @Volatile
        private var toolkitStarted = false

        /** Once per process; also works when AWT (Compose Desktop) is already running. */
        private fun startToolkit() = synchronized(this) {
            if (toolkitStarted) return
            Platform.setImplicitExit(false)
            val started = CountDownLatch(1)
            try {
                Platform.startup { started.countDown() }
            } catch (alreadyRunning: IllegalStateException) {
                started.countDown()
            }
            started.await(10, TimeUnit.SECONDS)
            toolkitStarted = true
        }
    }
}
