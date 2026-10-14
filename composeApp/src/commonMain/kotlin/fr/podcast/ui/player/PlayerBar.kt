package fr.podcast.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.podcast.player.NowPlaying
import fr.podcast.shared.home.EpisodeItem
import fr.podcast.ui.components.Artwork

/** Wide windows: every control at the bottom, as on the wireframes; the title opens the full player. */
@Composable
fun PlayerBar(item: EpisodeItem, nowPlaying: NowPlaying?, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val playback = LocalPlayback.current
    val queue by playback.queue.collectAsState()
    val state = nowPlaying?.state
    Surface(modifier.fillMaxWidth(), tonalElevation = 3.dp) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
                Artwork(item.podcast, size = 48.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(item.episode.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(item.podcast.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(16.dp))
            SkipButton(forward = false, onClick = { playback.seekBy(-SKIP_BACK) }, size = 40.dp, enabled = nowPlaying != null)
            PlayPauseButton(
                playing = state?.playing == true,
                buffering = state?.buffering == true,
                onClick = { if (nowPlaying == null) playback.play(item.episode.id) else playback.togglePlayPause() },
            )
            SkipButton(forward = true, onClick = { playback.seekBy(SKIP_FORWARD) }, size = 40.dp, enabled = nowPlaying != null)
            Spacer(Modifier.width(16.dp))
            PositionSlider(
                positionSeconds = state?.positionSeconds ?: item.episode.positionSeconds.toDouble(),
                durationSeconds = nowPlaying?.durationSeconds ?: item.episode.durationSeconds.toDouble(),
                onSeek = playback::seekTo,
                enabled = nowPlaying != null,
                modifier = Modifier.weight(1.3f),
            )
            Spacer(Modifier.width(8.dp))
            SpeedButton(speed = state?.speed ?: 1f, onClick = playback::cycleSpeed)
            NextButton(enabled = queue.isNotEmpty(), onClick = playback::skipToNext)
        }
    }
}
