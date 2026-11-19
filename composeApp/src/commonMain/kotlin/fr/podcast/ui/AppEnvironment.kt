package fr.podcast.ui

import fr.podcast.player.AudioPlayer
import fr.podcast.player.PlaybackController
import fr.podcast.player.SimulatedAudioPlayer
import fr.podcast.shared.account.Account
import fr.podcast.shared.account.KeyValueStore
import fr.podcast.shared.api.podcastHttpClient
import fr.podcast.shared.repository.FakePodcastRepository
import fr.podcast.shared.repository.PodcastClient
import fr.podcast.shared.repository.RemotePodcastRepository
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * What each platform entry point provides to the shared UI. Created once per process: playback
 * goes on while screens (or the Android activity) come and go.
 */
class AppEnvironment(
    /** Where accounts are saved (preferences, user defaults…). */
    val keyValueStore: KeyValueStore,
    /** Shown in the server's list of devices of the user. */
    val deviceName: String,
    /** Pre-filled server address; the Android emulator reaches the host at 10.0.2.2. */
    val defaultServerUrl: String,
    /** The platform's player (Media3, AVPlayer, JavaFX Media, ADR 0008). */
    private val player: AudioPlayer,
    val httpClient: HttpClient = podcastHttpClient(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Plays the episodes of the active account. */
    val playback = PlaybackController(player, scope)

    /** The demo plays without sound or network. */
    val demoPlayback by lazy {
        demoStarted = true
        PlaybackController(SimulatedAudioPlayer(scope), scope)
    }
    private var demoStarted = false

    // One client per account (and token) for the whole process: when the screens are created
    // again (Android activity), playback keeps going with the same client.
    private val clients = mutableMapOf<String, PodcastClient>()
    private val demoClient by lazy { FakePodcastRepository() }

    fun clientFor(account: Account?): PodcastClient =
        if (account == null) demoClient else clients.getOrPut("${account.id} ${account.token}") { RemotePodcastRepository(account, httpClient) }

    /** The controller in use (the demo's or the account's), for the platform's own controls: desktop menu bar. */
    var activePlayback: PlaybackController = playback
        private set

    /** Only one controller plays at a time: the demo's or the account's. */
    fun stopOtherThan(active: PlaybackController) {
        activePlayback = active
        if (active !== playback) playback.attach(null)
        if (demoStarted && active !== demoPlayback) demoPlayback.attach(null)
    }

    /** Before the process ends (desktop): frees the player. */
    fun release() = player.release()
}
