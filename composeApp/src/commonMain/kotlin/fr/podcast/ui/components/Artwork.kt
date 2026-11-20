package fr.podcast.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.podcast.shared.design.artworkColor
import fr.podcast.shared.design.initials
import fr.podcast.shared.model.Podcast
import fr.podcast.ui.theme.rgb

/**
 * The podcast's artwork from its server (ADR 0013); its initials on its color while it loads,
 * without an image, and in previews.
 */
@Composable
fun Artwork(podcast: Podcast, size: Dp, modifier: Modifier = Modifier) {
    val images = LocalArtworkImages.current
    val image by produceState(images?.cached(podcast), podcast.id, podcast.artworkUrl, images) {
        if (value == null && images != null) value = images.image(podcast)
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size / 6))
            .background(rgb(podcast.artworkColor)),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = image
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        } else {
            Text(
                text = podcast.initials,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = if (size >= 56.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelLarge,
            )
        }
    }
}
