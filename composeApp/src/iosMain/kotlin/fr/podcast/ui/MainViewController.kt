package fr.podcast.ui

import androidx.compose.ui.window.ComposeUIViewController
import fr.podcast.shared.account.UserDefaultsKeyValueStore
import platform.UIKit.UIDevice

@Suppress("FunctionName", "unused") // Called from Swift.
fun MainViewController() = ComposeUIViewController {
    App(
        AppEnvironment(
            keyValueStore = UserDefaultsKeyValueStore(),
            deviceName = UIDevice.currentDevice.name,
            // The simulator shares the Mac's network: localhost is the development server.
            defaultServerUrl = "http://localhost:8080",
        ),
    )
}
