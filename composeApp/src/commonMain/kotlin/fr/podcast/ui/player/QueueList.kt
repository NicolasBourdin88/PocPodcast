package fr.podcast.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.podcast.player.PlaybackController
import fr.podcast.shared.home.EpisodeItem
import fr.podcast.shared.i18n.Strings
import fr.podcast.ui.components.Artwork

/**
 * The queue ("up next") as rows of a LazyColumn: tap to play, move up, remove.
 * Shared by the side panel of wide windows and the full-screen player.
 */
fun LazyListScope.queueItems(queue: List<EpisodeItem>, playback: PlaybackController, strings: Strings) {
    item(key = "queue-title") {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(strings.queue, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (queue.isNotEmpty()) TextButton(onClick = playback::clearQueue) { Text(strings.clearQueue) }
        }
    }
    if (queue.isEmpty()) {
        item(key = "queue-empty") {
            Text(
                strings.emptyQueue,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
    itemsIndexed(queue, key = { _, item -> "queue-${item.episode.id}" }) { index, item ->
        val episodeId = item.episode.id
        ListItem(
            modifier = Modifier.clickable { playback.play(episodeId) },
            leadingContent = { Artwork(item.podcast, size = 40.dp) },
            headlineContent = { Text(item.episode.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text("${item.podcast.title} · ${strings.duration(item.episode.durationSeconds)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            trailingContent = {
                Row {
                    if (index > 0) {
                        IconButton(onClick = { playback.moveInQueue(episodeId, index - 1) }) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = strings.moveUp)
                        }
                    }
                    IconButton(onClick = { playback.dequeue(episodeId) }) {
                        Icon(Icons.Default.Close, contentDescription = strings.removeFromQueue)
                    }
                }
            },
        )
    }
}
