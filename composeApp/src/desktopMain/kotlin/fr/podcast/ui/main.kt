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
        App(environment)
    }
}
