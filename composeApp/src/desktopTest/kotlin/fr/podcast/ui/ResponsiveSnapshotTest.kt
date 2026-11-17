package fr.podcast.ui

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import fr.podcast.shared.account.Account
import fr.podcast.shared.account.AccountsState
import fr.podcast.shared.i18n.Language
import fr.podcast.shared.navigation.Destination
import fr.podcast.player.PlaybackController
import fr.podcast.player.SimulatedAudioPlayer
import fr.podcast.shared.repository.FakePodcastRepository
import fr.podcast.ui.player.LocalPlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import fr.podcast.ui.account.SettingsScreen
import fr.podcast.ui.account.SignInScreen
import fr.podcast.ui.account.accountsSection
import fr.podcast.ui.theme.PodcastTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Renders the screens at each window size, in every language, and writes PNGs to build/snapshots,
 * so the responsive layouts can be checked without launching the app.
 */
class ResponsiveSnapshotTest {

    private val sizes = mapOf(
        "compact" to (390 to 844),
        "compact-height" to (844 to 390),
        "medium" to (700 to 900),
        "expanded" to (1000 to 800),
        "large" to (1400 to 900),
    )

    private val accounts = AccountsState(
        accounts = listOf(
            Account("http://localhost:8080", "Podcast (dev)", "alice", "Alice", "t1"),
            Account("http://localhost:8081", "Serveur B", "bob", "Bob", "t2"),
        ),
        activeId = "alice@http://localhost:8080",
    )

    @Test
    fun layoutClasses() {
        sizes.forEach { (name, size) ->
            val (width, height) = size
            val expected = WindowLayout.entries.first { it.name.kebab() == name }
            assertEquals(expected, WindowLayout.from(width.dp, height.dp), name)
        }
    }

    @Test
    fun renderEveryLayout() {
        for ((name, size) in sizes) for (language in Language.entries) {
            render("home-$name-${language.tag}", size) { Home(language, Destination.Home) }
        }
    }

    @Test
    fun renderAccountScreens() {
        for (language in Language.entries) {
            render("sign-in-compact-${language.tag}", sizes.getValue("compact")) {
                PodcastTheme(language) {
                    Surface { SignInScreen("http://localhost:8080", signIn = { _, _, _ -> error("not called") }, onSignedIn = {}, onDemo = {}) }
                }
            }
            render("settings-large-${language.tag}", sizes.getValue("large")) { Home(language, Destination.Settings) }
            render("settings-compact-${language.tag}", sizes.getValue("compact")) { Home(language, Destination.Settings) }
            // Tall enough for every section: devices and, for an administrator, the users (ADR 0009).
            render("settings-full-${language.tag}", 1000 to 1700) { Home(language, Destination.Settings) }
        }
    }

    @Test
    fun renderLibraryScreens() {
        for (language in Language.entries) {
            val tag = language.tag
            render("explore-compact-$tag", sizes.getValue("compact")) { Home(language, Destination.Explore, query = "kotlin") }
            render("explore-large-$tag", sizes.getValue("large")) { Home(language, Destination.Explore, query = "kotlin") }
            render("library-compact-$tag", sizes.getValue("compact")) { Home(language, Destination.Library) }
            render("podcast-compact-$tag", sizes.getValue("compact")) { Home(language, Destination.Library, podcastId = "p2") }
            render("library-expanded-$tag", sizes.getValue("expanded")) { Home(language, Destination.Library, podcastId = "p2") }
            render("library-large-$tag", sizes.getValue("large")) { Home(language, Destination.Library, podcastId = "p1") }
            // Shared by Alice, read-only (ADR 0009).
            render("shared-podcast-compact-$tag", sizes.getValue("compact")) { Home(language, Destination.Library, podcastId = "p5") }
            render("shared-library-expanded-$tag", sizes.getValue("expanded")) { Home(language, Destination.Library, podcastId = "p5") }
        }
    }

    @Test
    fun renderPlaylists() {
        for (language in Language.entries) {
            val tag = language.tag
            render("playlists-compact-$tag", sizes.getValue("compact")) { Home(language, Destination.Playlists) }
            // Static, the demo user's: its actions and its feed (ADR 0010).
            render("playlist-compact-$tag", sizes.getValue("compact")) { Home(language, Destination.Playlists, playlistId = "pl1") }
            // Dynamic, next to the list.
            render("playlists-large-$tag", sizes.getValue("large")) { Home(language, Destination.Playlists, playlistId = "pl2") }
        }
    }

    @Test
    fun renderPlayer() {
        for (language in Language.entries) {
            val tag = language.tag
            render("player-compact-$tag", sizes.getValue("compact")) { Home(language, Destination.Home, playing = "e2", playerOpen = true) }
            render("player-compact-height-$tag", sizes.getValue("compact-height")) {
                Home(language, Destination.Home, playing = "e2", playerOpen = true)
            }
            render("player-bar-large-$tag", sizes.getValue("large")) { Home(language, Destination.Home, playing = "e2") }
        }
    }

    @Composable
    private fun Home(
        language: Language,
        destination: Destination,
        podcastId: String? = null,
        query: String = "",
        playing: String? = null,
        playerOpen: Boolean = false,
        playlistId: String? = null,
    ) {
        val repository = remember { FakePodcastRepository() }
        // The demo player, paused on [playing], with two episodes in the queue.
        val playback = remember {
            val scope = CoroutineScope(Dispatchers.Unconfined)
            PlaybackController(SimulatedAudioPlayer(scope), scope).apply {
                attach(repository)
                enqueue("e1")
                enqueue("e3")
                playing?.let {
                    play(it)
                    pause()
                }
            }
        }
        // Same tree as App(): theme, then a Surface for background and content colors.
        PodcastTheme(language) {
            Surface {
                CompositionLocalProvider(LocalPlayback provides playback) {
                    MainScaffold(
                        client = repository,
                        accountLabel = "Alice · Podcast (dev)",
                        initialDestination = destination,
                        initialPodcastId = podcastId,
                        initialQuery = query,
                        initialPlayerOpen = playerOpen,
                        initialPlaylistId = playlistId,
                        settings = { modifier ->
                            SettingsScreen(repository, onProfileChanged = {}, modifier) { accountsSection(accounts, demo = false, {}, {}, {}, {}) }
                        },
                    )
                }
            }
        }
    }

    private fun render(name: String, size: Pair<Int, Int>, content: @Composable () -> Unit) {
        val outDir = File("build/snapshots").apply { mkdirs() }
        val scene = ImageComposeScene(size.first, size.second, Density(1f), content = content)
        try {
            scene.render(0) // first frame launches the state loading
            val image = scene.render(1_000_000_000)
            val data = requireNotNull(image.encodeToData(EncodedImageFormat.PNG))
            File(outDir, "$name.png").writeBytes(data.bytes)
        } finally {
            scene.close()
        }
    }

    private fun String.kebab() = replace(Regex("([a-z])([A-Z])"), "$1-$2").lowercase()
}
