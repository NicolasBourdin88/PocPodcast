package fr.podcast.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.podcast.shared.home.EpisodeItem
import fr.podcast.shared.home.HomeState
import fr.podcast.ui.components.Artwork
import fr.podcast.ui.player.EpisodePlayButton
import fr.podcast.ui.player.LocalPlayback
import fr.podcast.ui.player.QueueToggleButton
import fr.podcast.ui.theme.LocalStrings
import kotlin.math.ceil

/** Home screen content. [twoColumns] puts "resume" and "latest" side by side. */
@Composable
fun HomeScreen(state: HomeState, twoColumns: Boolean, modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    if (twoColumns) {
        Row(modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LazyColumn(Modifier.weight(1f)) { resumeSection(strings.resumeListening, state.resume) }
            LazyColumn(Modifier.weight(1f)) { latestSection(strings.latestEpisodes, state.latest) }
        }
    } else {
        LazyColumn(modifier.fillMaxSize()) {
            resumeSection(strings.resumeListening, state.resume)
            latestSection(strings.latestEpisodes, state.latest)
        }
    }
}

private fun LazyListScope.sectionTitle(title: String) {
    item(key = "title-$title") {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

private fun LazyListScope.resumeSection(title: String, items: List<EpisodeItem>) {
    if (items.isEmpty()) return
    sectionTitle(title)
    items(items, key = { "resume-${it.episode.id}" }) { ResumeCard(it) }
}

private fun LazyListScope.latestSection(title: String, items: List<EpisodeItem>) {
    sectionTitle(title)
    items(items, key = { "latest-${it.episode.id}" }) { EpisodeRow(it) }
}

@Composable
private fun ResumeCard(item: EpisodeItem) {
    val strings = LocalStrings.current
    val episode = item.episode
    // The episode playing shows the player's live position.
    val nowPlaying by LocalPlayback.current.nowPlaying.collectAsState()
    val live = nowPlaying?.takeIf { it.item.episode.id == episode.id }
    val progress = live?.progress ?: episode.progress
    val remainingMinutes = live?.let { ceil((it.durationSeconds - it.state.positionSeconds).coerceAtLeast(0.0) / 60).toInt() } ?: episode.remainingMinutes
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(item.podcast, size = 64.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.podcast.title, style = MaterialTheme.typography.labelMedium)
                Text(
                    episode.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text(strings.remaining(remainingMinutes), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(8.dp))
            EpisodePlayButton(episode.id)
        }
    }
}

@Composable
private fun EpisodeRow(item: EpisodeItem) {
    val strings = LocalStrings.current
    val episode = item.episode
    ListItem(
        leadingContent = { Artwork(item.podcast, size = 48.dp) },
        overlineContent = { Text("${item.podcast.title} · ${strings.shortDate(episode.publishedOn())}") },
        headlineContent = { Text(episode.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text((listOf(strings.duration(episode.durationSeconds), strings.status(episode)) + strings.marks(episode)).joinToString(" · ")) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                QueueToggleButton(episode.id)
                EpisodePlayButton(episode.id)
            }
        },
    )
}
