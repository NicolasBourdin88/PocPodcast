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

    /** Playlists (ADR 0010): the destination, and adding an episode to one. */
    val Playlist: ImageVector = icon("Playlist", "M19,9H2V11H19V9M19,5H2V7H19V5M2,15H15V13H2V15M17,13V19L22,16L17,13Z")
    val PlaylistAdd: ImageVector = icon(
        "PlaylistAdd",
        "M15,6H3V8H15V6M15,10H3V12H15V10M3,16H11V14H3V16M17,6V14.18C16.69,14.07 16.35,14 16,14A3,3 0 0,0 13,17A3,3 0 0,0 16,20A3,3 0 0,0 19,17V8H22V6H17Z",
    )

    /** An empty star, next to the filled one of material-icons-core, for ratings. */
    val StarBorder: ImageVector = icon(
        "StarBorder",
        "M12,15.39L8.24,17.66L9.23,13.38L5.91,10.5L10.29,10.13L12,6.09L13.71,10.13L18.09,10.5L14.77,13.38L15.76,17.66M22,9.24L14.81,8.63L12,2L9.19,8.63L2,9.24L7.45,13.97L5.82,21L12,17.27L18.18,21L16.54,13.97L22,9.24Z",
    )

    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
            .build()
}
