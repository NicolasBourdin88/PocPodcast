package fr.podcast.android

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import fr.podcast.shared.account.SharedPreferencesKeyValueStore
import fr.podcast.ui.App
import fr.podcast.ui.AppEnvironment

class MainActivity : ComponentActivity() {

    private val environment by lazy {
        AppEnvironment(
            keyValueStore = SharedPreferencesKeyValueStore(applicationContext),
            deviceName = "${Build.MANUFACTURER} ${Build.MODEL}",
            // The emulator reaches the development machine at 10.0.2.2.
            defaultServerUrl = "http://10.0.2.2:8080",
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { App(environment) }
    }
}
