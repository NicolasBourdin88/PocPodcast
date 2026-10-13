package fr.podcast.ui

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Renders the app at each window size and writes PNGs to build/snapshots,
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
        val outDir = File("build/snapshots").apply { mkdirs() }
        sizes.forEach { (name, size) ->
            val (width, height) = size
            val scene = ImageComposeScene(width, height, Density(1f)) { App() }
            try {
                scene.render(0) // first frame launches the state loading
                val image = scene.render(1_000_000_000)
                val data = requireNotNull(image.encodeToData(EncodedImageFormat.PNG))
                File(outDir, "$name.png").writeBytes(data.bytes)
            } finally {
                scene.close()
            }
        }
    }

    private fun String.kebab() = replace(Regex("([a-z])([A-Z])"), "$1-$2").lowercase()
}
