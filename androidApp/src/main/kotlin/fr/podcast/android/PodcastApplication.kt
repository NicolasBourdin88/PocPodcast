package fr.podcast.android

import android.app.Application
import android.os.Build
import fr.podcast.player.AndroidAudioPlayer
import fr.podcast.shared.account.SharedPreferencesKeyValueStore
import fr.podcast.ui.AppEnvironment

/** Holds what must outlive the activity: playback goes on in the background (ADR 0008). */
class PodcastApplication : Application() {
    val environment by lazy {
        AppEnvironment(
            keyValueStore = SharedPreferencesKeyValueStore(this),
            deviceName = "${Build.MANUFACTURER} ${Build.MODEL}",
            // The emulator reaches the development machine at 10.0.2.2.
            defaultServerUrl = "http://10.0.2.2:8080",
            player = AndroidAudioPlayer(this),
        )
    }
}
