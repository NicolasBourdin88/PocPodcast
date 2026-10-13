package fr.podcast.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.podcast.shared.model.Podcast
import kotlin.math.absoluteValue

private val artworkColors = listOf(
    Color(0xFF6750A4), Color(0xFF0B6E4F), Color(0xFFB3261E), Color(0xFF1F5FA8), Color(0xFF8A5A00),
)

/** Placeholder artwork (initials on a colored square) until real images are loaded. */
@Composable
fun Artwork(podcast: Podcast, size: Dp, modifier: Modifier = Modifier) {
    val color = artworkColors[podcast.id.hashCode().absoluteValue % artworkColors.size]
    val initials = podcast.title.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size / 6))
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            style = if (size >= 56.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelLarge,
        )
    }
}
