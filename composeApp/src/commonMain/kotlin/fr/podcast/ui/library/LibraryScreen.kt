package fr.podcast.ui.library

import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.podcast.shared.api.userMessage
import fr.podcast.shared.model.Access
import fr.podcast.shared.model.RefreshStatus
import fr.podcast.shared.model.SharePermission
import fr.podcast.shared.model.SubscriptionItem
import fr.podcast.shared.repository.PodcastClient
import fr.podcast.shared.repository.SharedItem
import fr.podcast.ui.components.Artwork
import fr.podcast.ui.components.SectionTitle
import fr.podcast.ui.components.SystemBackHandler
import fr.podcast.ui.components.attempt
import fr.podcast.ui.player.EpisodePlayButton
import fr.podcast.ui.theme.LocalStrings
import kotlinx.coroutines.launch

private sealed interface LibraryState {
    data object Loading : LibraryState
    data class Loaded(val items: List<SubscriptionItem>, val shared: List<SharedItem>) : LibraryState
    data class Failed(val error: Throwable) : LibraryState
}

/**
 * Library: the subscriptions and what others share with the user (ADR 0009), then a podcast's
 * page. With [twoPanes] (wide windows) the list stays visible next to the open podcast; otherwise
 * the podcast replaces the list.
 */
@Composable
fun LibraryScreen(
    client: PodcastClient,
    openPodcastId: String?,
    onOpenPodcast: (String?) -> Unit,
    twoPanes: Boolean,
    onChanged: () -> Unit,
    modifier: Modifier = Modifier,
    initialSearch: String? = null,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val opml = rememberOpmlFiles()
    var state by remember(client) { mutableStateOf<LibraryState>(LibraryState.Loading) }
    var reloads by remember { mutableIntStateOf(0) }

    LaunchedEffect(client, reloads) {
        if (state !is LibraryState.Loaded) state = LibraryState.Loading
        // Shared playlists are in Playlists.
        state = attempt { LibraryState.Loaded(client.subscriptionItems(), client.sharedWithMe().filter { it.playlist == null }) }
            .fold({ it }, { LibraryState.Failed(it) })
    }

    fun replace(item: SubscriptionItem) {
        val loaded = state as? LibraryState.Loaded ?: return
        state = loaded.copy(
            items = loaded.items.map { if (it.subscription.id == item.subscription.id) item else it },
            shared = loaded.shared.map { if (it.subscription?.subscription?.id == item.subscription.id) it.copy(subscription = item) else it },
        )
    }

    fun removed(item: SubscriptionItem) {
        val loaded = state as? LibraryState.Loaded ?: return
        state = loaded.copy(items = loaded.items.filterNot { it.subscription.id == item.subscription.id })
        onOpenPodcast(null)
        onChanged()
        scope.launch { snackbar.showSnackbar(strings.unsubscribedFrom(item.podcast.title)) }
    }

    /** The user follows a podcast shared with them: their own subscription replaces the share on screen. */
    fun subscribed(item: SubscriptionItem) {
        reloads++
        onChanged()
        scope.launch { snackbar.showSnackbar(strings.subscribedTo(item.podcast.title)) }
    }

    fun decline(shared: SharedItem) {
        scope.launch {
            attempt { client.unshare(shared.share.id) }.fold(
                onSuccess = {
                    val loaded = state as? LibraryState.Loaded ?: return@fold
                    state = loaded.copy(shared = loaded.shared.filterNot { it.share.id == shared.share.id })
                    if (shared.subscription?.podcast?.id == openPodcastId) onOpenPodcast(null)
                },
                onFailure = { snackbar.showSnackbar(it.userMessage(strings)) },
            )
        }
    }

    fun exportOpml(files: OpmlFiles) {
        scope.launch {
            attempt { files.save("podcasts.opml", client.exportOpml()) }.fold(
                onSuccess = { name -> name?.let { snackbar.showSnackbar(strings.opmlSaved(it)) } },
                onFailure = { snackbar.showSnackbar(it.userMessage(strings)) },
            )
        }
    }

    fun importOpml(files: OpmlFiles) {
        scope.launch {
            attempt { files.open()?.let { client.importOpml(it) } }.fold(
                onSuccess = { report ->
                    if (report != null) {
                        reloads++
                        onChanged()
                        snackbar.showSnackbar(strings.opmlReport(report.imported, report.alreadySubscribed, report.failed.size))
                    }
                },
                onFailure = { snackbar.showSnackbar(it.userMessage(strings)) },
            )
        }
    }

    val items = (state as? LibraryState.Loaded)?.items
    val shared = (state as? LibraryState.Loaded)?.shared.orEmpty()
    // One's own subscription first: a podcast can also be shared by someone else.
    val open = items?.find { it.podcast.id == openPodcastId }
        ?: shared.firstNotNullOfOrNull { it.subscription?.takeIf { item -> item.podcast.id == openPodcastId } }
    SystemBackHandler(enabled = openPodcastId != null && !twoPanes) { onOpenPodcast(null) }

    // The subscriptions, or the search of their episodes (ADR 0012).
    var tab by rememberSaveable { mutableIntStateOf(if (initialSearch != null) 1 else 0) }
    val list: @Composable (Modifier) -> Unit = { listModifier ->
        Column(listModifier) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(strings.subscriptionsTab) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(strings.episodesTab) })
            }
            val content = Modifier.fillMaxWidth().weight(1f)
            when (val current = state) {
                LibraryState.Loading -> Box(content, Alignment.Center) { CircularProgressIndicator() }
                is LibraryState.Failed -> Centered(current.error.userMessage(strings), content, strings.retry) { reloads++ }
                is LibraryState.Loaded -> if (tab == 1) {
                    EpisodeSearch(client, current.items, content, initialSearch.orEmpty())
                } else {
                    SubscriptionList(
                        items = current.items,
                        shared = current.shared,
                        selectedPodcastId = openPodcastId.takeIf { twoPanes },
                        onOpen = { onOpenPodcast(it.podcast.id) },
                        onDecline = ::decline,
                        onExport = opml?.let { { exportOpml(it) } },
                        onImport = opml?.let { { importOpml(it) } },
                        modifier = content,
                    )
                }
            }
        }
    }
    val detail: @Composable (Modifier, SubscriptionItem) -> Unit = { detailModifier, item ->
        PodcastScreen(
            client = client,
            item = item,
            onBack = if (twoPanes) null else ({ onOpenPodcast(null) }),
            onChanged = ::replace,
            onUnsubscribed = { removed(item) },
            snackbar = snackbar,
            modifier = detailModifier,
            onSubscribed = ::subscribed,
            onDecline = shared.find { it.subscription?.subscription?.id == item.subscription.id }?.let { { decline(it) } },
        )
    }

    Box(modifier.fillMaxSize()) {
        when {
            twoPanes -> Row(Modifier.fillMaxSize()) {
                list(Modifier.width(360.dp).fillMaxHeight())
                VerticalDivider()
                when {
                    open != null -> detail(Modifier.weight(1f), open)
                    items != null -> Centered(strings.choosePodcast, Modifier.weight(1f))
                    else -> Box(Modifier.weight(1f))
                }
            }
            open != null -> detail(Modifier.fillMaxSize(), open)
            else -> list(Modifier.fillMaxSize())
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun SubscriptionList(
    items: List<SubscriptionItem>,
    shared: List<SharedItem>,
    selectedPodcastId: String?,
    onOpen: (SubscriptionItem) -> Unit,
    onDecline: (SharedItem) -> Unit,
    onExport: (() -> Unit)?,
    onImport: (() -> Unit)?,
    modifier: Modifier,
) {
    val strings = LocalStrings.current
    // Words and a tag (ADR 0012), applied as the server does.
    var text by rememberSaveable { mutableStateOf("") }
    var tag by rememberSaveable { mutableStateOf<String?>(null) }
    val tags = items.flatMap { it.subscription.tags }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
    val shown = items.filter { it.matches(text, tag) }
    LazyColumn(modifier) {
        item(key = "filter") {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(strings.filterLibrary) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
            )
        }
        if (tags.isNotEmpty()) {
            item(key = "tags") {
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tags.forEach { each ->
                        val selected = each.equals(tag, ignoreCase = true)
                        FilterChip(selected = selected, onClick = { tag = if (selected) null else each }, label = { Text(each) })
                    }
                }
            }
        }
        if (onExport != null && onImport != null) {
            item(key = "opml") {
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = onExport) { Text(strings.opmlExport) }
                    OutlinedButton(onClick = onImport) { Text(strings.opmlImport) }
                }
            }
        }
        if (shown.isEmpty()) {
            item(key = "empty") {
                Text(
                    if (items.isEmpty()) strings.emptyLibrary else strings.noSubscriptionFound,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(shown, key = { it.subscription.id }) { item ->
            val podcast = item.podcast
            ListItem(
                modifier = Modifier.clickable { onOpen(item) },
                colors = selectedColors(podcast.id == selectedPodcastId),
                leadingContent = { Artwork(podcast, size = 48.dp) },
                overlineContent = item.subscription.tags.takeIf { it.isNotEmpty() }?.let { { Text(it.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                headlineContent = { Text(podcast.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text(item.subscription.note?.takeIf { it.isNotBlank() } ?: podcast.author, maxLines = 2, overflow = TextOverflow.Ellipsis)
                },
                trailingContent = { RefreshBadge(podcast.refreshStatus) },
            )
        }
        if (shared.isNotEmpty()) {
            item(key = "shared") { SectionTitle(strings.sharedWithMe) }
            items(shared, key = { "share-${it.share.id}" }) { entry ->
                entry.subscription?.let { SharedSubscriptionItem(entry, it, selected = it.podcast.id == selectedPodcastId, onOpen = onOpen) }
                entry.episode?.let { episode ->
                    ListItem(
                        leadingContent = { Artwork(episode.podcast, size = 48.dp) },
                        overlineContent = { Text(strings.recommendedBy(entry.share.owner.displayName)) },
                        headlineContent = { Text(episode.episode.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = entry.share.message?.let { message ->
                            { Text(message, fontStyle = FontStyle.Italic, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                EpisodePlayButton(episode.episode.id)
                                IconButton(onClick = { onDecline(entry) }) { Icon(Icons.Default.Close, contentDescription = strings.decline) }
                            }
                        },
                    )
                }
            }
        }
    }
}

/** A subscription someone shares with the user: whose, what they may do with it, and the owner's message. */
@Composable
private fun SharedSubscriptionItem(entry: SharedItem, item: SubscriptionItem, selected: Boolean, onOpen: (SubscriptionItem) -> Unit) {
    val strings = LocalStrings.current
    val permission = if (item.subscription.access == Access.WRITE) SharePermission.WRITE else SharePermission.READ
    ListItem(
        modifier = Modifier.clickable { onOpen(item) },
        colors = selectedColors(selected),
        leadingContent = { Artwork(item.podcast, size = 48.dp) },
        overlineContent = { Text(strings.sharedBy(entry.share.owner.displayName)) },
        headlineContent = { Text(item.podcast.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(strings.permission(permission))
                entry.share.message?.let { Text(it, fontStyle = FontStyle.Italic, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        },
    )
}

@Composable
private fun selectedColors(selected: Boolean) =
    if (selected) ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else ListItemDefaults.colors()

/** Shown while a feed has never been read, or when its last reading failed. */
@Composable
internal fun RefreshBadge(status: RefreshStatus) {
    val strings = LocalStrings.current
    when (status) {
        RefreshStatus.PENDING -> Text(strings.refreshPending, style = MaterialTheme.typography.labelSmall)
        RefreshStatus.FAILED -> Text(strings.refreshFailed, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        RefreshStatus.OK -> Unit
    }
}

@Composable
internal fun Centered(text: String, modifier: Modifier, action: String? = null, onAction: () -> Unit = {}) {
    Box(modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            action?.let { Button(onClick = onAction) { Text(it) } }
        }
    }
}
