package fr.podcast.ui

import androidx.compose.ui.window.ComposeUIViewController
import fr.podcast.player.IosAudioPlayer
import fr.podcast.shared.account.UserDefaultsKeyValueStore
import platform.UIKit.UIDevice

/** One for the whole process: playback goes on in the background (ADR 0008). */
private val environment by lazy {
    AppEnvironment(
        keyValueStore = UserDefaultsKeyValueStore(),
        deviceName = UIDevice.currentDevice.name,
        // The simulator shares the Mac's network: localhost is the development server.
        defaultServerUrl = "http://localhost:8080",
        player = IosAudioPlayer(),
    )
}

@Suppress("FunctionName", "unused") // Called from Swift.
fun MainViewController() = ComposeUIViewController { App(environment) }
