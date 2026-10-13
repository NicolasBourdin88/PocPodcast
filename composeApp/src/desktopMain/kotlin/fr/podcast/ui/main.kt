package fr.podcast.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import fr.podcast.shared.account.PreferencesKeyValueStore
import java.util.prefs.Preferences

fun main() = application {
    val environment = remember {
        AppEnvironment(
            keyValueStore = PreferencesKeyValueStore(Preferences.userRoot().node("fr/podcast/desktop")),
            deviceName = "Desktop (${System.getProperty("os.name")})",
            defaultServerUrl = "http://localhost:8080",
        )
    }
    Window(
        onCloseRequest = ::exitApplication,
        title = "Podcast",
        state = rememberWindowState(width = 1280.dp, height = 800.dp),
    ) {
        App(environment)
    }
}
