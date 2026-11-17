package fr.podcast.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.podcast.ui.theme.LocalStrings

/** Adds the episode to the user's favorites, or takes it out (ADR 0010). */
@Composable
fun FavoriteButton(favorite: Boolean, onToggle: (Boolean) -> Unit) {
    val strings = LocalStrings.current
    IconToggleButton(checked = favorite, onCheckedChange = onToggle) {
        Icon(
            if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            contentDescription = if (favorite) strings.removeFavorite else strings.addFavorite,
            tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Five stars: touching the n-th rates n; touching the current rating clears it (null). [rating] 0 or
 * null shows empty stars.
 */
@Composable
fun StarRating(rating: Int?, onRate: (Int?) -> Unit, modifier: Modifier = Modifier, starSize: Dp = 24.dp) {
    val strings = LocalStrings.current
    val current = rating ?: 0
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        for (stars in 1..5) {
            IconButton(onClick = { onRate(if (stars == current) null else stars) }, modifier = Modifier.size(starSize + 16.dp)) {
                Icon(
                    if (stars <= current) Icons.Default.Star else PodcastIcons.StarBorder,
                    contentDescription = if (stars == current) strings.clearRating else strings.rateAs(stars),
                    tint = if (stars <= current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(starSize),
                )
            }
        }
    }
}
