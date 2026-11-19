package fr.podcast.ui

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
        state = rememberWindowState(width = 1280.dp, height = 800.dp),
    ) {
        PlaybackMenu(environment)
        App(environment)
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
