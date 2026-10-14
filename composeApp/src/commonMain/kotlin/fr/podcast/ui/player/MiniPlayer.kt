package fr.podcast.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.podcast.player.NowPlaying
import fr.podcast.shared.home.EpisodeItem
import fr.podcast.ui.components.Artwork

/**
 * Narrow windows: the episode being played (or the one to resume) above the navigation.
 * Tapping it opens the full-screen player.
 */
@Composable
fun MiniPlayer(item: EpisodeItem, nowPlaying: NowPlaying?, singleLine: Boolean, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val playback = LocalPlayback.current
    val state = nowPlaying?.state
    Surface(modifier.fillMaxWidth().clickable(onClick = onOpen), tonalElevation = 3.dp) {
        Column {
            LinearProgressIndicator(progress = { nowPlaying?.progress ?: item.episode.progress }, modifier = Modifier.fillMaxWidth())
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = if (singleLine) 4.dp else 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(item.podcast, size = if (singleLine) 32.dp else 44.dp)
                Spacer(Modifier.width(12.dp))
                if (singleLine) {
                    Text(
                        "${item.episode.title} · ${item.podcast.title}",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Column(Modifier.weight(1f)) {
                        Text(item.episode.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(item.podcast.title, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                }
                PlayPauseButton(
                    playing = state?.playing == true,
                    buffering = state?.buffering == true,
                    onClick = { if (nowPlaying == null) playback.play(item.episode.id) else playback.togglePlayPause() },
                    size = if (singleLine) 36.dp else 44.dp,
                    tonal = true,
                )
            }
        }
    }
}
