package fr.podcast.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import fr.podcast.ui.App

class MainActivity : ComponentActivity() {

    private val environment get() = (application as PodcastApplication).environment

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { App(environment) }
    }

    /** Leaving the app: the position reaches the server, in case the process is stopped. */
    override fun onStop() {
        super.onStop()
        environment.playback.saveNow()
    }
}
