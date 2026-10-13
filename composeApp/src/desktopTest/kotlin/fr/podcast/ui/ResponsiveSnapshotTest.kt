package fr.podcast.ui

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import fr.podcast.shared.account.Account
import fr.podcast.shared.account.AccountsState
import fr.podcast.shared.i18n.Language
import fr.podcast.shared.navigation.Destination
import fr.podcast.shared.repository.FakePodcastRepository
import fr.podcast.ui.account.AccountsScreen
import fr.podcast.ui.account.SignInScreen
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
        }
    }

    @Composable
    private fun Home(language: Language, destination: Destination) {
        // Same tree as App(): theme, then a Surface for background and content colors.
        PodcastTheme(language) {
            Surface {
                MainScaffold(
                    repository = FakePodcastRepository(),
                    accountLabel = "Alice · Podcast (dev)",
                    initialDestination = destination,
                    settings = { modifier -> AccountsScreen(accounts, demo = false, {}, {}, {}, {}, modifier) },
                )
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
