package fr.podcast.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.podcast.shared.api.userMessage
import fr.podcast.shared.model.Episode
import fr.podcast.shared.model.FileStatus
import fr.podcast.shared.model.SubscriptionItem
import fr.podcast.shared.repository.PodcastClient
import fr.podcast.ui.components.Artwork
import fr.podcast.ui.components.PodcastIcons
import fr.podcast.ui.components.attempt
import fr.podcast.ui.player.EpisodePlayButton
import fr.podcast.ui.player.QueueToggleButton
import fr.podcast.ui.theme.LocalStrings
import kotlinx.coroutines.launch

/**
 * A subscribed podcast: its note and settings, then its episodes. Every change is sent to the
 * server, and the answer replaces the local copy ([onChanged]).
 */
@Composable
fun PodcastScreen(
    client: PodcastClient,
    item: SubscriptionItem,
    onBack: (() -> Unit)?,
    onChanged: (SubscriptionItem) -> Unit,
    onUnsubscribed: () -> Unit,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val podcast = item.podcast
    val subscription = item.subscription
    var note by rememberSaveable(subscription.id) { mutableStateOf(subscription.note.orEmpty()) }
    var episodes by remember(podcast.id) { mutableStateOf<List<Episode>?>(null) }
    var reloads by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var confirmUnsubscribe by remember { mutableStateOf(false) }

    LaunchedEffect(podcast.id, reloads) {
        attempt { client.podcastEpisodes(podcast.id) }
            .onSuccess { episodes = it }
            .onFailure { snackbar.showSnackbar(it.userMessage(strings)) }
    }

    /** Runs [action]; its failure is shown in the snackbar. */
    fun act(action: suspend () -> Unit) {
        scope.launch { attempt { action() }.onFailure { snackbar.showSnackbar(it.userMessage(strings)) } }
    }

    fun update(note: String?, autoDownload: Boolean) = act {
        onChanged(client.updateSubscription(subscription.id, note?.takeIf { it.isNotBlank() }, autoDownload))
    }

    fun replaceEpisode(id: String) = act {
        val fresh = client.episode(id) ?: return@act
        episodes = episodes?.map { if (it.id == id) fresh else it }
    }

    LazyColumn(modifier) {
        item(key = "header") {
            Column(
                Modifier.widthIn(max = 760.dp).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    onBack?.let {
                        IconButton(onClick = it) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.back) }
                        Spacer(Modifier.width(4.dp))
                    }
                    Artwork(podcast, size = 88.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(podcast.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(podcast.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        RefreshBadge(podcast.refreshStatus)
                    }
                    if (refreshing) {
                        CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(
                            onClick = {
                                act {
                                    refreshing = true
                                    try {
                                        client.refreshPodcast(podcast.id)?.let { onChanged(item.copy(podcast = it)) }
                                        reloads++
                                    } finally {
                                        refreshing = false
                                    }
                                }
                            },
                        ) { Icon(Icons.Default.Refresh, contentDescription = strings.refresh) }
                    }
                }
                if (podcast.description.isNotBlank()) {
                    Text(podcast.description, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(strings.note) },
                    placeholder = { Text(strings.notePlaceholder) },
                    minLines = 2,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalButton(
                        onClick = { update(note, subscription.autoDownload) },
                        enabled = note.trim() != subscription.note.orEmpty().trim(),
                    ) { Text(strings.save) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = subscription.autoDownload, onCheckedChange = { update(subscription.note, it) })
                        Spacer(Modifier.width(8.dp))
                        Text(strings.autoDownload)
                    }
                    OutlinedButton(
                        onClick = { confirmUnsubscribe = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text(strings.unsubscribe) }
                }
                Text(strings.episodes, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            }
        }
        when (val list = episodes) {
            null -> item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { CircularProgressIndicator() }
            }
            else -> {
                if (list.isEmpty()) {
                    item(key = "empty") { Text(strings.noEpisodes, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                items(list, key = { it.id }) { episode ->
                    EpisodeRow(
                        episode = episode,
                        onToggleKept = {
                            act {
                                if (episode.kept) client.unkeep(episode.id) else client.keep(episode.id)
                                replaceEpisode(episode.id)
                            }
                        },
                        onDownload = {
                            act {
                                client.download(episode.id)
                                replaceEpisode(episode.id)
                            }
                        },
                    )
                }
            }
        }
    }

    if (confirmUnsubscribe) {
        AlertDialog(
            onDismissRequest = { confirmUnsubscribe = false },
            title = { Text(strings.unsubscribe) },
            text = { Text(strings.unsubscribeConfirm(podcast.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmUnsubscribe = false
                        act {
                            client.unsubscribe(subscription.id)
                            onUnsubscribed()
                        }
                    },
                ) { Text(strings.unsubscribe, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmUnsubscribe = false }) { Text(strings.cancel) } },
        )
    }
}

@Composable
private fun EpisodeRow(episode: Episode, onToggleKept: () -> Unit, onDownload: () -> Unit) {
    val strings = LocalStrings.current
    val details = listOfNotNull(
        strings.status(episode),
        strings.fileStatus(episode.fileStatus),
        strings.chapters.takeIf { episode.chaptersUrl != null },
        strings.transcript.takeIf { episode.transcripts.isNotEmpty() },
    )
    ListItem(
        overlineContent = { Text("${strings.shortDate(episode.publishedOn())} · ${strings.duration(episode.durationSeconds)}") },
        headlineContent = { Text(episode.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(details.joinToString(" · "))
                // Secondary actions under the text, so that narrow screens keep room for the title.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    QueueToggleButton(episode.id)
                    IconToggleButton(checked = episode.kept, onCheckedChange = { onToggleKept() }) {
                        Icon(
                            if (episode.kept) PodcastIcons.Bookmark else PodcastIcons.BookmarkBorder,
                            contentDescription = strings.action(if (episode.kept) "unkeep" else "keep"),
                        )
                    }
                    if (episode.fileStatus == FileStatus.NONE || episode.fileStatus == FileStatus.FAILED) {
                        IconButton(onClick = onDownload) { Icon(PodcastIcons.Download, contentDescription = strings.action("download")) }
                    }
                }
            }
        },
        trailingContent = { EpisodePlayButton(episode.id) },
    )
}
