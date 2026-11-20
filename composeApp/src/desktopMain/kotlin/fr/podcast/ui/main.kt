package fr.podcast.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import fr.podcast.shared.navigation.Destination
import kotlinx.coroutines.delay
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import fr.podcast.player.JavaFxAudioPlayer
import fr.podcast.shared.account.PreferencesKeyValueStore
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.prefs.Preferences
import kotlin.time.Duration.Companion.seconds
import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import fr.podcast.player.PlaybackController
import fr.podcast.ui.theme.platformLanguage

fun main() = application {
    val environment = remember {
        AppEnvironment(
            keyValueStore = PreferencesKeyValueStore(Preferences.userRoot().node("fr/podcast/desktop")),
            deviceName = "Desktop (${System.getProperty("os.name")})",
            defaultServerUrl = "http://localhost:8080",
            player = JavaFxAudioPlayer(),
        )
    }
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    Window(
        onCloseRequest = {
            closing = true
            // The last position reaches the server before the app quits (3 s at most).
            scope.launch {
                withTimeoutOrNull(3.seconds) { environment.playback.flush() }
                environment.release()
                exitApplication()
            }
        },
        visible = !closing,
        title = "Podcast",
        state = rememberWindowState(width = Debug.windowSize.first.dp, height = Debug.windowSize.second.dp),
    ) {
        PlaybackMenu(environment)
        Snapshot(Debug.snapshot) {
            App(environment, initialDestination = Debug.destination)
        }
    }
}

/**
 * Development: environment variables to check the app without clicking through it, as the
 * SwiftUI app's launch arguments do.
 * - `PODCAST_TAB=library` opens a tab;
 * - `PODCAST_WINDOW=1440x900` sizes the window;
 * - `PODCAST_SNAPSHOT=<file.png>` saves the window's content once loaded (after
 *   `PODCAST_SNAPSHOT_DELAY` seconds, 8 by default): no screen recording permission needed.
 */
private object Debug {
    val destination = System.getenv("PODCAST_TAB")
        ?.let { tab -> Destination.entries.firstOrNull { it.name.equals(tab, ignoreCase = true) } }
        ?: Destination.Home
    val windowSize = System.getenv("PODCAST_WINDOW")?.split('x')?.mapNotNull { it.trim().toIntOrNull() }
        ?.takeIf { it.size == 2 }?.let { it[0] to it[1] }
        ?: (1280 to 800)
    val snapshot: String? = System.getenv("PODCAST_SNAPSHOT")
    val snapshotDelay = System.getenv("PODCAST_SNAPSHOT_DELAY")?.toLongOrNull()?.seconds ?: 8.seconds
}

/** Draws [content], and with a [path] records it into a layer saved as a PNG once loaded. */
@Composable
private fun Snapshot(path: String?, content: @Composable () -> Unit) {
    if (path == null) return content()
    val layer = rememberGraphicsLayer()
    Box(
        Modifier.fillMaxSize().drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
        },
    ) { content() }
    LaunchedEffect(layer) {
        delay(Debug.snapshotDelay)
        val image = Image.makeFromBitmap(layer.toImageBitmap().asSkiaBitmap())
        File(path).writeBytes(requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes)
        println("snapshot: $path")
    }
}

/**
 * The native menu bar (ADR 0013): playback from the keyboard. The shortcuts leave text editing
 * alone: ⌘[ and ⌘] (Ctrl elsewhere) as back and forward in a browser.
 */
@Composable
private fun FrameWindowScope.PlaybackMenu(environment: AppEnvironment) {
    val strings = platformLanguage().strings
    val mac = System.getProperty("os.name").lowercase().contains("mac")
    fun shortcut(key: Key, shift: Boolean = false) = KeyShortcut(key, meta = mac, ctrl = !mac, shift = shift)
    val playback = { environment.activePlayback }
    MenuBar {
        Menu(strings.playbackMenu) {
            Item(strings.playPause, shortcut = shortcut(Key.P, shift = true), onClick = { playback().togglePlayPause() })
            Item(strings.skipBack(PlaybackController.SKIP_BACK_SECONDS.toInt()), shortcut = shortcut(Key.LeftBracket)) {
                playback().seekBy(-PlaybackController.SKIP_BACK_SECONDS)
            }
            Item(strings.skipForward(PlaybackController.SKIP_FORWARD_SECONDS.toInt()), shortcut = shortcut(Key.RightBracket)) {
                playback().seekBy(PlaybackController.SKIP_FORWARD_SECONDS)
            }
            Separator()
            Item(strings.next, shortcut = shortcut(Key.RightBracket, shift = true), onClick = { playback().skipToNext() })
            Item(strings.changeSpeed, onClick = { playback().cycleSpeed() })
        }
    }
}
