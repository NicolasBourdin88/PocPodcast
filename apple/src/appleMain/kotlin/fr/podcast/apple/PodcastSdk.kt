package fr.podcast.apple

import fr.podcast.player.AppleAudioPlayer
import fr.podcast.player.PlaybackController
import fr.podcast.player.SimulatedAudioPlayer
import fr.podcast.shared.account.Account
import fr.podcast.shared.account.AccountService
import fr.podcast.shared.account.AccountStore
import fr.podcast.shared.account.UserDefaultsKeyValueStore
import fr.podcast.shared.api.podcastHttpClient
import fr.podcast.shared.api.userMessage
import fr.podcast.shared.home.HomeState
import fr.podcast.shared.home.LoadHomeState
import fr.podcast.shared.i18n.Language
import fr.podcast.shared.i18n.Strings
import fr.podcast.shared.model.Episode
import fr.podcast.shared.repository.FakePodcastRepository
import fr.podcast.shared.repository.PodcastClient
import fr.podcast.shared.repository.RemotePodcastRepository
import kotlinx.coroutines.MainScope
import platform.Foundation.NSLocale
import platform.Foundation.preferredLanguages

/**
 * What the SwiftUI client (iOS and macOS, ADR 0013) takes from the Kotlin SDK, in one object
 * created at launch: the accounts saved on the device, one client per account, the demo, the
 * player and the texts. The screens are SwiftUI; the contract stays :shared's (models, API client
 * following the links, rules, texts in French and English).
 */
class PodcastSdk(deviceName: String) {
    private val scope = MainScope()
    private val httpClient = podcastHttpClient()
    private val accountService = AccountService(httpClient, deviceName)

    /** The simulator and the Mac reach the development server at localhost. */
    val defaultServerUrl = "http://localhost:8080"

    val language: Language = (NSLocale.preferredLanguages.firstOrNull() as? String)?.let(Language::fromTag) ?: Language.Default
    val strings: Strings get() = language.strings

    /** The accounts saved on this device, one of them active (ADR 0006). */
    val accounts = AccountStore(UserDefaultsKeyValueStore())

    /** Plays the active account's episodes (AVPlayer, ADR 0008). */
    val playback = PlaybackController(AppleAudioPlayer(), scope)

    /** The demo plays without sound or network. */
    val demoPlayback: PlaybackController by lazy { PlaybackController(SimulatedAudioPlayer(scope), scope) }

    private val demoClient by lazy { FakePodcastRepository() }
    private val clients = mutableMapOf<String, PodcastClient>()

    /** The client of [account] (one per account and token), the demo's without one. */
    fun client(account: Account?): PodcastClient =
        if (account == null) demoClient else clients.getOrPut("${account.id} ${account.token}") { RemotePodcastRepository(account, httpClient) }

    /** Signs in and saves the account, which becomes the active one. */
    @Throws(Exception::class)
    suspend fun signIn(serverUrl: String, username: String, password: String): Account =
        accountService.signIn(serverUrl, username, password).also(accounts::save)

    /** Revokes this device's token when the server answers; the account is forgotten anyway. */
    @Throws(Exception::class)
    suspend fun signOut(account: Account) {
        accountService.signOut(account)
        accounts.remove(account.id)
    }

    /** Only one controller plays at a time: the demo's or the account's. */
    fun usePlayback(demo: Boolean, client: PodcastClient): PlaybackController {
        val active = if (demo) demoPlayback else playback
        val other = if (demo) playback else demoPlayback.takeIf { demoStarted }
        other?.attach(null)
        if (demo) demoStarted = true
        active.attach(client)
        return active
    }
    private var demoStarted = false

    /** Home: what to resume and the new episodes, as every client shows it. */
    @Throws(Exception::class)
    suspend fun home(client: PodcastClient): HomeState = LoadHomeState(client)()

    /** The sentence to show for a failure, in the user's language. */
    fun message(error: Throwable): String = error.userMessage(strings)

    fun publishedOn(episode: Episode): String = strings.shortDate(episode.publishedOn())
}
