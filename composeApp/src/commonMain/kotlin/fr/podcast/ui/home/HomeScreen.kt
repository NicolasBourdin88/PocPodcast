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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.podcast.shared.home.EpisodeItem
import fr.podcast.shared.home.HomeState
import fr.podcast.shared.model.EpisodeStatus
import fr.podcast.ui.components.Artwork

/** Home screen content. [twoColumns] puts "resume" and "latest" side by side. */
@Composable
fun HomeScreen(state: HomeState, twoColumns: Boolean, modifier: Modifier = Modifier) {
    if (twoColumns) {
        Row(modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LazyColumn(Modifier.weight(1f)) { resumeSection(state.resume) }
            LazyColumn(Modifier.weight(1f)) { latestSection(state.latest) }
        }
    } else {
        LazyColumn(modifier.fillMaxSize()) {
            resumeSection(state.resume)
            latestSection(state.latest)
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

private fun LazyListScope.resumeSection(items: List<EpisodeItem>) {
    if (items.isEmpty()) return
    sectionTitle("Reprendre l'écoute")
    items(items, key = { "resume-${it.episode.id}" }) { ResumeCard(it) }
}

private fun LazyListScope.latestSection(items: List<EpisodeItem>) {
    sectionTitle("Nouveaux épisodes")
    items(items, key = { "latest-${it.episode.id}" }) { EpisodeRow(it) }
}

@Composable
private fun ResumeCard(item: EpisodeItem) {
    val episode = item.episode
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
                LinearProgressIndicator(progress = { episode.progress }, modifier = Modifier.fillMaxWidth())
                Text("${episode.remainingMinutes} min restantes", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalIconButton(onClick = {}) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Reprendre")
            }
        }
    }
}

@Composable
private fun EpisodeRow(item: EpisodeItem) {
    val episode = item.episode
    val status = when (episode.status) {
        EpisodeStatus.NEW -> "Nouveau"
        EpisodeStatus.IN_PROGRESS -> "${episode.remainingMinutes} min restantes"
        EpisodeStatus.PLAYED -> "Écouté"
    }
    ListItem(
        leadingContent = { Artwork(item.podcast, size = 48.dp) },
        overlineContent = { Text("${item.podcast.title} · ${episode.publishedLabel}") },
        headlineContent = { Text(episode.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text("${episode.durationLabel} · $status") },
        trailingContent = {
            FilledTonalIconButton(onClick = {}) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Lire")
            }
        },
    )
}
