package fr.podcast.ui.playlists

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import fr.podcast.shared.api.userMessage
import fr.podcast.shared.model.Access
import fr.podcast.shared.model.Playlist
import fr.podcast.shared.model.PlaylistKind
import fr.podcast.shared.repository.PodcastClient
import fr.podcast.ui.components.FormDialog
import fr.podcast.ui.components.attempt
import fr.podcast.ui.theme.LocalStrings

/**
 * Adds an episode to a static playlist the user may change: one of theirs, or one shared with them to
 * modify. [onAdded] follows the server's answer.
 */
@Composable
fun AddToPlaylistDialog(client: PodcastClient, episodeId: String, onDismiss: () -> Unit, onAdded: (Playlist) -> Unit) {
    val strings = LocalStrings.current
    val choices by produceState<Result<List<Playlist>>?>(null, client) {
        value = attempt {
            val own = client.playlists()
            val shared = client.sharedWithMe().mapNotNull { it.playlist }.filter { it.access == Access.WRITE }
            (own + shared).filter { it.kind == PlaylistKind.STATIC }
        }
    }
    var selected by remember { mutableStateOf<Playlist?>(null) }

    FormDialog(
        title = strings.addToPlaylist,
        confirmLabel = strings.addToPlaylist,
        onDismiss = onDismiss,
        canConfirm = selected != null,
        onConfirm = {
            val playlist = selected!!
            client.addToPlaylist(playlist.id, episodeId)
            onAdded(playlist)
        },
    ) {
        when (val current = choices) {
            null -> CircularProgressIndicator(Modifier.padding(8.dp))
            else -> current.fold(
                onSuccess = { playlists ->
                    if (playlists.isEmpty()) {
                        Text(strings.noPlaylistToAddTo, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Column {
                            playlists.forEach { playlist ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .selectable(selected = playlist == selected, role = Role.RadioButton, onClick = { selected = playlist })
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = playlist == selected, onClick = null)
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text(playlist.title)
                                        Text(
                                            listOfNotNull(strings.episodeCount(playlist.episodeCount), playlist.ownerName?.let(strings::playlistSharedBy))
                                                .joinToString(" · "),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                onFailure = { Text(it.userMessage(strings), color = MaterialTheme.colorScheme.error) },
            )
        }
    }
}
