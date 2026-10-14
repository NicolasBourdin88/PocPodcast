package fr.podcast.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Material Symbols paths missing from material-icons-core (the extended set is too large to ship). */
object PodcastIcons {
    val Download: ImageVector = icon("Download", "M5,20h14v-2H5V20zM19,9h-4V3H9v6H5l7,7L19,9z")
    val Bookmark: ImageVector = icon("Bookmark", "M17,3H7c-1.1,0 -1.99,0.9 -1.99,2L5,21l7,-3 7,3V5c0,-1.1 -0.9,-2 -2,-2z")
    val BookmarkBorder: ImageVector =
        icon("BookmarkBorder", "M17,3H7c-1.1,0 -1.99,0.9 -1.99,2L5,21l7,-3 7,3V5c0,-1.1 -0.9,-2 -2,-2zM17,18l-5,-2.18L7,18V5h10v13z")

    val Pause: ImageVector = icon("Pause", "M6,19H10V5H6V19M14,5V19H18V5H14Z")
    val SkipNext: ImageVector = icon("SkipNext", "M6,18L14.5,12L6,6V18M16,6V18H18V6H16Z")

    /** Circular arrows for "back 15 s" and "forward 30 s"; the number is drawn on top. */
    val Replay: ImageVector = icon("Replay", "M12,5V1L7,6L12,11V7C15.31,7 18,9.69 18,13C18,16.31 15.31,19 12,19C8.69,19 6,16.31 6,13H4C4,17.42 7.58,21 12,21C16.42,21 20,17.42 20,13C20,8.58 16.42,5 12,5Z")
    val Forward: ImageVector = icon("Forward", "M12,5V1L17,6L12,11V7C8.69,7 6,9.69 6,13C6,16.31 8.69,19 12,19C15.31,19 18,16.31 18,13H20C20,17.42 16.42,21 12,21C7.58,21 4,17.42 4,13C4,8.58 7.58,5 12,5Z")
    val QueueAdd: ImageVector = icon("QueueAdd", "M14,10H2V12H14V10M14,6H2V8H14V6M18,14V10H16V14H12V16H16V20H18V16H22V14H18M2,16H10V14H2V16Z")
    val QueueAdded: ImageVector = icon("QueueAdded", "M14,10H2V12H14V10M14,6H2V8H14V6M2,16H10V14H2V16M21.5,11.5L23,13L16,20L11.5,15.5L13,14L16,17L21.5,11.5Z")

    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
            .build()
}
