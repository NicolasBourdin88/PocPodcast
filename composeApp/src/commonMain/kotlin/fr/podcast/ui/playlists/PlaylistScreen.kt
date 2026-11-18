package fr.podcast.ui.playlists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.podcast.shared.api.userMessage
import fr.podcast.shared.home.EpisodeItem
import fr.podcast.shared.model.Access
import fr.podcast.shared.model.Playlist
import fr.podcast.shared.model.PlaylistKind
import fr.podcast.shared.model.PlaylistOrder
import fr.podcast.shared.repository.PlaylistDetails
import fr.podcast.shared.repository.PodcastClient
import fr.podcast.ui.components.Artwork
import fr.podcast.ui.components.PodcastIcons
import fr.podcast.ui.components.attempt
import fr.podcast.ui.player.EpisodePlayButton
import fr.podcast.ui.player.LocalPlayback
import fr.podcast.ui.sharing.ShareDialog
import fr.podcast.ui.theme.LocalStrings
import kotlinx.coroutines.launch

private enum class PlaylistDialogs { Edit, Share, Delete }

/**
 * A playlist and its episodes (ADR 0010), with what the user's rights allow: anyone plays it; whoever
 * may modify it edits it, shuffles a random one and changes a static one's episodes; its owner also
 * shares it, publishes its RSS feed and deletes it.
 */
@Composable
fun PlaylistScreen(
    client: PodcastClient,
    playlistId: String,
    onBack: (() -> Unit)?,
    onChanged: () -> Unit,
    onDeleted: () -> Unit,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val playback = LocalPlayback.current
    var details by remember(playlistId) { mutableStateOf<PlaylistDetails?>(null) }
    var reloads by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<PlaylistDialogs?>(null) }

    LaunchedEffect(client, playlistId, reloads) {
        attempt { client.playlist(playlistId) }
            .onSuccess { details = it }
            .onFailure { snackbar.showSnackbar(it.userMessage(strings)) }
    }

    /** Runs [action]; its failure is shown in the snackbar; on success the playlist is read again. */
    fun act(done: String? = null, action: suspend () -> Unit) {
        scope.launch {
            attempt { action() }.fold(
                onSuccess = {
                    reloads++
                    onChanged()
                    done?.let { snackbar.showSnackbar(it) }
                },
                onFailure = { snackbar.showSnackbar(it.userMessage(strings)) },
            )
        }
    }

    val current = details
    if (current == null) {
        Box(modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val playlist = current.playlist
    val owner = playlist.access == Access.OWNER
    val canModify = playlist.access != Access.READ
    val editable = canModify && playlist.kind == PlaylistKind.STATIC

    LazyColumn(modifier) {
        item(key = "header") {
            Column(Modifier.widthIn(max = 760.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    onBack?.let {
                        IconButton(onClick = it) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.back) }
                        Spacer(Modifier.width(4.dp))
                    }
                    Icon(PodcastIcons.Playlist, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(playlist.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${strings.playlistKind(playlist.kind)} · ${strings.episodeCount(playlist.episodeCount)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                playlist.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                playlist.rules?.let { Text(strings.playlistRules(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                playlist.ownerName?.let { ownerName ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("${strings.playlistSharedBy(ownerName)} · ${strings.permission(playlist.access.toPermission())}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (current.episodes.isNotEmpty()) {
                        Button(onClick = { playback.playAll(current.episodes.map { it.episode.id }) }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(strings.playAll)
                        }
                    }
                    if (canModify && playlist.rules?.order == PlaylistOrder.RANDOM) {
                        OutlinedButton(onClick = { act { client.shufflePlaylist(playlist.id) } }) { Text(strings.shuffle) }
                    }
                    if (canModify) OutlinedButton(onClick = { dialog = PlaylistDialogs.Edit }) { Text(strings.editPlaylist) }
                    if (owner) {
                        OutlinedButton(onClick = { dialog = PlaylistDialogs.Share }) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(strings.share)
                        }
                        OutlinedButton(
                            onClick = { dialog = PlaylistDialogs.Delete },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) { Text(strings.deletePlaylist) }
                    }
                }
                if (owner) FeedSection(playlist, onPublish = { act(strings.feedPublished(playlist.title)) { client.publishFeed(playlist.id) } },
                    onWithdraw = { act { client.unpublishFeed(playlist.id) } })
            }
        }
        if (current.episodes.isEmpty()) {
            item(key = "empty") { Text(strings.emptyPlaylist, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        itemsIndexed(current.episodes, key = { _, item -> item.episode.id }) { index, item ->
            PlaylistEpisode(
                item = item,
                onMoveUp = if (editable && index > 0) ({ act { client.moveInPlaylist(playlist.id, item.episode.id, index - 1) } }) else null,
                onRemove = if (editable) ({ act { client.removeFromPlaylist(playlist.id, item.episode.id) } }) else null,
            )
        }
    }

    val close = { dialog = null }
    when (dialog) {
        null -> Unit
        PlaylistDialogs.Edit -> PlaylistDialog(initial = playlist, onDismiss = close) { title, description, rules ->
            details = client.updatePlaylist(playlist.id, title, description, rules)
            onChanged()
        }
        PlaylistDialogs.Share -> ShareDialog(
            client = client,
            title = strings.shareTitle(playlist.title),
            confirmLabel = strings.share,
            withPermission = true,
            onDismiss = close,
        ) { user, permission, message ->
            client.sharePlaylist(playlist.id, user.id, permission, message)
            scope.launch { snackbar.showSnackbar(strings.sharedWithUser(user.displayName)) }
        }
        PlaylistDialogs.Delete -> AlertDialog(
            onDismissRequest = close,
            title = { Text(strings.deletePlaylist) },
            text = { Text(strings.deletePlaylistConfirm(playlist.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        close()
                        scope.launch {
                            attempt { client.deletePlaylist(playlist.id) }.fold(
                                onSuccess = { onDeleted() },
                                onFailure = { snackbar.showSnackbar(it.userMessage(strings)) },
                            )
                        }
                    },
                ) { Text(strings.delete, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = close) { Text(strings.cancel) } },
        )
    }
}

/** The RSS feed, for its owner: its secret address to give to any podcast app, a new one, or none. */
@Composable
private fun FeedSection(playlist: Playlist, onPublish: () -> Unit, onWithdraw: () -> Unit) {
    val strings = LocalStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(strings.feed, style = MaterialTheme.typography.titleSmall)
        Text(strings.feedHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        playlist.feedUrl?.let { url ->
            SelectionContainer { Text(url, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onPublish) { Text(if (playlist.feedUrl == null) strings.publishFeed else strings.newFeedAddress) }
            if (playlist.feedUrl != null) TextButton(onClick = onWithdraw) { Text(strings.unpublishFeed) }
        }
    }
}

@Composable
private fun PlaylistEpisode(item: EpisodeItem, onMoveUp: (() -> Unit)?, onRemove: (() -> Unit)?) {
    val strings = LocalStrings.current
    ListItem(
        leadingContent = { Artwork(item.podcast, size = 48.dp) },
        overlineContent = { Text("${item.podcast.title} · ${strings.shortDate(item.episode.publishedOn())}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        headlineContent = { Text(item.episode.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text((listOf(strings.status(item.episode)) + strings.marks(item.episode)).joinToString(" · "), modifier = Modifier.weight(1f, fill = false))
                onMoveUp?.let { IconButton(onClick = it) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = strings.moveUp) } }
                onRemove?.let { IconButton(onClick = it) { Icon(Icons.Default.Close, contentDescription = strings.removeFromPlaylist) } }
            }
        },
        trailingContent = { EpisodePlayButton(item.episode.id) },
    )
}

private fun Access.toPermission() =
    if (this == Access.WRITE) fr.podcast.shared.model.SharePermission.WRITE else fr.podcast.shared.model.SharePermission.READ
