package fr.podcast.ui.playlists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.podcast.shared.api.userMessage
import fr.podcast.shared.model.Playlist
import fr.podcast.shared.repository.PodcastClient
import fr.podcast.shared.repository.SharedItem
import fr.podcast.ui.components.PodcastIcons
import fr.podcast.ui.components.SectionTitle
import fr.podcast.ui.components.SystemBackHandler
import fr.podcast.ui.components.attempt
import fr.podcast.ui.library.Centered
import fr.podcast.ui.theme.LocalStrings

private sealed interface PlaylistsState {
    data object Loading : PlaylistsState
    data class Loaded(val own: List<Playlist>, val shared: List<SharedItem>) : PlaylistsState
    data class Failed(val error: Throwable) : PlaylistsState
}

/**
 * Playlists (ADR 0010): the user's own and those shared with them, then a playlist's page. With
 * [twoPanes] (wide windows) the list stays next to the open playlist.
 */
@Composable
fun PlaylistsScreen(
    client: PodcastClient,
    openPlaylistId: String?,
    onOpen: (String?) -> Unit,
    twoPanes: Boolean,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val snackbar = remember { SnackbarHostState() }
    var state by remember(client) { mutableStateOf<PlaylistsState>(PlaylistsState.Loading) }
    var reloads by remember { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }

    LaunchedEffect(client, reloads) {
        if (state !is PlaylistsState.Loaded) state = PlaylistsState.Loading
        state = attempt {
            PlaylistsState.Loaded(client.playlists(), client.sharedWithMe().filter { it.playlist != null })
        }.fold({ it }, { PlaylistsState.Failed(it) })
    }
    SystemBackHandler(enabled = openPlaylistId != null && !twoPanes) { onOpen(null) }

    val list: @Composable (Modifier) -> Unit = { listModifier ->
        when (val current = state) {
            PlaylistsState.Loading -> Box(listModifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            is PlaylistsState.Failed -> Centered(current.error.userMessage(strings), listModifier, strings.retry) { reloads++ }
            is PlaylistsState.Loaded -> LazyColumn(listModifier) {
                item(key = "new") {
                    FilledTonalButton(onClick = { creating = true }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(strings.newPlaylist)
                    }
                }
                if (current.own.isEmpty()) {
                    item(key = "empty") { Text(strings.emptyPlaylists, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                items(current.own, key = { it.id }) { playlist ->
                    PlaylistItem(playlist, selected = twoPanes && playlist.id == openPlaylistId, onOpen = { onOpen(playlist.id) })
                }
                if (current.shared.isNotEmpty()) {
                    item(key = "shared") { SectionTitle(strings.sharedPlaylists) }
                    items(current.shared, key = { "share-${it.share.id}" }) { entry ->
                        val playlist = entry.playlist!!
                        PlaylistItem(playlist, selected = twoPanes && playlist.id == openPlaylistId, message = entry.share.message, onOpen = { onOpen(playlist.id) })
                    }
                }
            }
        }
    }
    val detail: @Composable (Modifier, String) -> Unit = { detailModifier, id ->
        PlaylistScreen(
            client = client,
            playlistId = id,
            onBack = if (twoPanes) null else ({ onOpen(null) }),
            onChanged = { reloads++ },
            onDeleted = {
                onOpen(null)
                reloads++
            },
            snackbar = snackbar,
            modifier = detailModifier,
        )
    }

    Box(modifier.fillMaxSize()) {
        when {
            twoPanes -> Row(Modifier.fillMaxSize()) {
                list(Modifier.width(360.dp).fillMaxHeight())
                VerticalDivider()
                when (openPlaylistId) {
                    null -> Centered(strings.choosePlaylist, Modifier.weight(1f))
                    else -> detail(Modifier.weight(1f), openPlaylistId)
                }
            }
            openPlaylistId != null -> detail(Modifier.fillMaxSize(), openPlaylistId)
            else -> list(Modifier.fillMaxSize())
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (creating) {
        PlaylistDialog(initial = null, onDismiss = { creating = false }) { title, description, rules ->
            val created = client.createPlaylist(title, description, rules)
            reloads++
            onOpen(created.playlist.id)
        }
    }
}

/** A playlist in the list: its kind and size, whose it is when shared, and the owner's message. */
@Composable
private fun PlaylistItem(playlist: Playlist, selected: Boolean, message: String? = null, onOpen: () -> Unit) {
    val strings = LocalStrings.current
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        colors = if (selected) ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else ListItemDefaults.colors(),
        leadingContent = { Icon(PodcastIcons.Playlist, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        overlineContent = playlist.ownerName?.let { { Text(strings.playlistSharedBy(it)) } },
        headlineContent = { Text(playlist.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            androidx.compose.foundation.layout.Column {
                Text("${strings.playlistKind(playlist.kind)} · ${strings.episodeCount(playlist.episodeCount)}")
                message?.let { Text(it, fontStyle = FontStyle.Italic, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        },
    )
}
