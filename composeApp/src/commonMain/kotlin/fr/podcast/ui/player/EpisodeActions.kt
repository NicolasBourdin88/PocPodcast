package fr.podcast.ui.player

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import fr.podcast.ui.components.PodcastIcons
import fr.podcast.ui.theme.LocalStrings

/** Plays the episode, or pauses it when it is the one playing. */
@Composable
fun EpisodePlayButton(episodeId: String) {
    val strings = LocalStrings.current
    val playback = LocalPlayback.current
    val nowPlaying by playback.nowPlaying.collectAsState()
    val current = nowPlaying?.item?.episode?.id == episodeId
    val playing = current && nowPlaying?.state?.playing == true
    FilledTonalIconButton(onClick = { if (current) playback.togglePlayPause() else playback.play(episodeId) }) {
        Icon(if (playing) PodcastIcons.Pause else Icons.Default.PlayArrow, contentDescription = if (playing) strings.pause else strings.play)
    }
}

/** Adds the episode to the end of the queue, or takes it out. */
@Composable
fun QueueToggleButton(episodeId: String) {
    val strings = LocalStrings.current
    val playback = LocalPlayback.current
    val queue by playback.queue.collectAsState()
    val queued = queue.any { it.episode.id == episodeId }
    IconButton(onClick = { if (queued) playback.dequeue(episodeId) else playback.enqueue(episodeId) }) {
        Icon(
            if (queued) PodcastIcons.QueueAdded else PodcastIcons.QueueAdd,
            contentDescription = if (queued) strings.removeFromQueue else strings.addToQueue,
        )
    }
}
