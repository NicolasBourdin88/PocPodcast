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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.key
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
import fr.podcast.shared.model.Access
import fr.podcast.shared.model.Episode
import fr.podcast.shared.model.FileStatus
import fr.podcast.shared.model.Share
import fr.podcast.shared.model.SharePermission
import fr.podcast.shared.model.SubscriptionItem
import fr.podcast.shared.repository.PodcastClient
import fr.podcast.ui.components.Artwork
import fr.podcast.ui.components.FavoriteButton
import fr.podcast.ui.components.PodcastIcons
import fr.podcast.ui.playlists.AddToPlaylistDialog
import fr.podcast.ui.components.attempt
import fr.podcast.ui.player.EpisodePlayButton
import fr.podcast.ui.player.QueueToggleButton
import fr.podcast.ui.sharing.ShareDialog
import fr.podcast.ui.theme.LocalStrings
import kotlinx.coroutines.launch

/**
 * A podcast seen through a subscription: its note and settings, then its episodes. Every change
 * is sent to the server, and the answer replaces the local copy ([onChanged]).
 *
 * What can be changed follows the viewer's access (ADR 0009): the owner shares it and sees whom
 * with; a subscription shared with them is read-only, or its note and automatic download can be
 * changed; they may follow the podcast themselves ([onSubscribed]) or remove the share ([onDecline]).
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
    onSubscribed: (SubscriptionItem) -> Unit = {},
    onDecline: (() -> Unit)? = null,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val podcast = item.podcast
    val subscription = item.subscription
    val owner = subscription.access == Access.OWNER
    val editable = subscription.access != Access.READ
    var note by rememberSaveable(subscription.id) { mutableStateOf(subscription.note.orEmpty()) }
    var episodes by remember(podcast.id) { mutableStateOf<List<Episode>?>(null) }
    var shares by remember(subscription.id) { mutableStateOf<List<Share>>(emptyList()) }
    var reloads by remember { mutableIntStateOf(0) }
    var shareReloads by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var confirmUnsubscribe by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    var recommending by remember { mutableStateOf<Episode?>(null) }
    var addingToPlaylist by remember { mutableStateOf<Episode?>(null) }

    LaunchedEffect(podcast.id, reloads) {
        attempt { client.podcastEpisodes(podcast.id) }
            .onSuccess { episodes = it }
            .onFailure { snackbar.showSnackbar(it.userMessage(strings)) }
    }
    LaunchedEffect(subscription.id, owner, shareReloads) {
        if (owner) attempt { client.sharesOf(subscription.id) }.onSuccess { shares = it }
    }

    /** Runs [action]; its failure is shown in the snackbar. */
    fun act(action: suspend () -> Unit) {
        scope.launch { attempt { action() }.onFailure { snackbar.showSnackbar(it.userMessage(strings)) } }
    }

    fun tell(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
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
                subscription.ownerName?.let { ownerName ->
                    SharedByBanner(
                        ownerName = ownerName,
                        permission = if (editable) SharePermission.WRITE else SharePermission.READ,
                        onSubscribe = { act { onSubscribed(client.subscribe(podcast.feedUrl)) } },
                        onDecline = onDecline,
                    )
                }
                if (editable) {
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(strings.note) },
                        placeholder = { Text(strings.notePlaceholder) },
                        minLines = 2,
                    )
                } else {
                    subscription.note?.takeIf { it.isNotBlank() }?.let { text ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(strings.ownerNote(subscription.ownerName.orEmpty()), style = MaterialTheme.typography.labelMedium)
                            Text(text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    if (editable) {
                        FilledTonalButton(
                            onClick = { update(note, subscription.autoDownload) },
                            enabled = note.trim() != subscription.note.orEmpty().trim(),
                        ) { Text(strings.save) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = subscription.autoDownload, onCheckedChange = { update(subscription.note, it) })
                            Spacer(Modifier.width(8.dp))
                            Text(strings.autoDownload)
                        }
                    }
                    if (owner) {
                        OutlinedButton(onClick = { sharing = true }) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(strings.share)
                        }
                        OutlinedButton(
                            onClick = { confirmUnsubscribe = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) { Text(strings.unsubscribe) }
                    }
                }
                if (shares.isNotEmpty()) {
                    SharedWithList(
                        shares = shares,
                        onPermission = { share, permission ->
                            act {
                                client.changePermission(share.id, permission)
                                shareReloads++
                            }
                        },
                        onStop = { share ->
                            act {
                                client.unshare(share.id)
                                shareReloads++
                            }
                        },
                    )
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
                        onRecommend = { recommending = episode },
                        onToggleFavorite = {
                            act {
                                client.setFavorite(episode.id, !episode.favorite)
                                replaceEpisode(episode.id)
                            }
                        },
                        onAddToPlaylist = { addingToPlaylist = episode },
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
    if (sharing) {
        ShareDialog(
            client = client,
            title = strings.shareTitle(podcast.title),
            confirmLabel = strings.share,
            withPermission = true,
            onDismiss = { sharing = false },
        ) { user, permission, message ->
            client.shareSubscription(subscription.id, user.id, permission, message)
            shareReloads++
            tell(strings.sharedWithUser(user.displayName))
        }
    }
    addingToPlaylist?.let { episode ->
        AddToPlaylistDialog(
            client = client,
            episodeId = episode.id,
            onDismiss = { addingToPlaylist = null },
            onAdded = { playlist -> tell(strings.addedTo(playlist.title)) },
        )
    }
    recommending?.let { episode ->
        ShareDialog(
            client = client,
            title = strings.recommendTitle(episode.title),
            confirmLabel = strings.recommend,
            withPermission = false,
            onDismiss = { recommending = null },
        ) { user, _, message ->
            client.recommend(episode.id, user.id, message)
            tell(strings.recommendedTo(user.displayName))
        }
    }
}

/** Whose subscription this is and what the viewer may do; follow it oneself, or remove it. */
@Composable
private fun SharedByBanner(ownerName: String, permission: SharePermission, onSubscribe: () -> Unit, onDecline: (() -> Unit)?) {
    val strings = LocalStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text("${strings.sharedBy(ownerName)} · ${strings.permission(permission)}", style = MaterialTheme.typography.bodyMedium)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onSubscribe) { Text(strings.subscribe) }
            onDecline?.let { OutlinedButton(onClick = it) { Text(strings.decline) } }
        }
    }
}

/** Whom the owner shares the subscription with: change what they may do, or stop sharing. */
@Composable
private fun SharedWithList(shares: List<Share>, onPermission: (Share, SharePermission) -> Unit, onStop: (Share) -> Unit) {
    val strings = LocalStrings.current
    Column {
        Text(strings.sharedWith, style = MaterialTheme.typography.titleSmall)
        shares.forEach { share -> key(share.id) {
            var menu by remember { mutableStateOf(false) }
            ListItem(
                headlineContent = { Text(share.grantee.displayName) },
                supportingContent = { Text(listOfNotNull(strings.permission(share.permission), share.message).joinToString(" · ")) },
                trailingContent = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = strings.moreActions) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            SharePermission.entries.filter { it != share.permission }.forEach { permission ->
                                DropdownMenuItem(
                                    text = { Text(strings.permission(permission)) },
                                    onClick = {
                                        menu = false
                                        onPermission(share, permission)
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(strings.stopSharing, color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    menu = false
                                    onStop(share)
                                },
                            )
                        }
                    }
                },
            )
        } }
    }
}

/**
 * An episode: the queue, the favorite and adding to a playlist at hand, the rarer actions (keep,
 * download, recommend) in a menu, so that narrow screens keep room.
 */
@Composable
private fun EpisodeRow(
    episode: Episode,
    onToggleKept: () -> Unit,
    onDownload: () -> Unit,
    onRecommend: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
) {
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
                    FavoriteButton(episode.favorite) { onToggleFavorite() }
                    IconButton(onClick = onAddToPlaylist) { Icon(PodcastIcons.PlaylistAdd, contentDescription = strings.addToPlaylist) }
                    episode.rating?.let { Text(strings.stars(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = strings.moreActions) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(strings.action(if (episode.kept) "unkeep" else "keep")) },
                                leadingIcon = { Icon(if (episode.kept) PodcastIcons.Bookmark else PodcastIcons.BookmarkBorder, contentDescription = null) },
                                onClick = {
                                    menu = false
                                    onToggleKept()
                                },
                            )
                            if (episode.fileStatus == FileStatus.NONE || episode.fileStatus == FileStatus.FAILED) {
                                DropdownMenuItem(
                                    text = { Text(strings.action("download")) },
                                    leadingIcon = { Icon(PodcastIcons.Download, contentDescription = null) },
                                    onClick = {
                                        menu = false
                                        onDownload()
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(strings.recommend) },
                                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                                onClick = {
                                    menu = false
                                    onRecommend()
                                },
                            )
                        }
                    }
                }
            }
        },
        trailingContent = { EpisodePlayButton(episode.id) },
    )
}
