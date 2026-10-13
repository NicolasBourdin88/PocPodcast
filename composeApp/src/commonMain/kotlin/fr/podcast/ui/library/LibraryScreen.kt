package fr.podcast.ui.library

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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.podcast.shared.api.userMessage
import fr.podcast.shared.model.RefreshStatus
import fr.podcast.shared.model.SubscriptionItem
import fr.podcast.shared.repository.PodcastClient
import fr.podcast.ui.components.Artwork
import fr.podcast.ui.components.SystemBackHandler
import fr.podcast.ui.components.attempt
import fr.podcast.ui.theme.LocalStrings
import kotlinx.coroutines.launch

private sealed interface LibraryState {
    data object Loading : LibraryState
    data class Loaded(val items: List<SubscriptionItem>) : LibraryState
    data class Failed(val error: Throwable) : LibraryState
}

/**
 * Library: the subscriptions, then a podcast's page. With [twoPanes] (wide windows) the list
 * stays visible next to the open podcast; otherwise the podcast replaces the list.
 */
@Composable
fun LibraryScreen(
    client: PodcastClient,
    openPodcastId: String?,
    onOpenPodcast: (String?) -> Unit,
    twoPanes: Boolean,
    onChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val opml = rememberOpmlFiles()
    var state by remember(client) { mutableStateOf<LibraryState>(LibraryState.Loading) }
    var reloads by remember { mutableIntStateOf(0) }

    LaunchedEffect(client, reloads) {
        if (state !is LibraryState.Loaded) state = LibraryState.Loading
        state = attempt { client.subscriptionItems() }.fold({ LibraryState.Loaded(it) }, { LibraryState.Failed(it) })
    }

    fun replace(item: SubscriptionItem) {
        val loaded = state as? LibraryState.Loaded ?: return
        state = LibraryState.Loaded(loaded.items.map { if (it.subscription.id == item.subscription.id) item else it })
    }

    fun removed(item: SubscriptionItem) {
        val loaded = state as? LibraryState.Loaded ?: return
        state = LibraryState.Loaded(loaded.items.filterNot { it.subscription.id == item.subscription.id })
        onOpenPodcast(null)
        onChanged()
        scope.launch { snackbar.showSnackbar(strings.unsubscribedFrom(item.podcast.title)) }
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
    val open = items?.find { it.podcast.id == openPodcastId }
    SystemBackHandler(enabled = openPodcastId != null && !twoPanes) { onOpenPodcast(null) }

    val list: @Composable (Modifier) -> Unit = { listModifier ->
        when (val current = state) {
            LibraryState.Loading -> Box(listModifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            is LibraryState.Failed -> Centered(current.error.userMessage(strings), listModifier, strings.retry) { reloads++ }
            is LibraryState.Loaded -> SubscriptionList(
                items = current.items,
                selectedPodcastId = openPodcastId.takeIf { twoPanes },
                onOpen = { onOpenPodcast(it.podcast.id) },
                onExport = opml?.let { { exportOpml(it) } },
                onImport = opml?.let { { importOpml(it) } },
                modifier = listModifier,
            )
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
    selectedPodcastId: String?,
    onOpen: (SubscriptionItem) -> Unit,
    onExport: (() -> Unit)?,
    onImport: (() -> Unit)?,
    modifier: Modifier,
) {
    val strings = LocalStrings.current
    LazyColumn(modifier) {
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
        if (items.isEmpty()) {
            item(key = "empty") {
                Text(
                    strings.emptyLibrary,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(items, key = { it.subscription.id }) { item ->
            val podcast = item.podcast
            val selected = podcast.id == selectedPodcastId
            ListItem(
                modifier = Modifier.clickable { onOpen(item) },
                colors = if (selected) {
                    ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                } else {
                    ListItemDefaults.colors()
                },
                leadingContent = { Artwork(podcast, size = 48.dp) },
                headlineContent = { Text(podcast.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text(item.subscription.note?.takeIf { it.isNotBlank() } ?: podcast.author, maxLines = 2, overflow = TextOverflow.Ellipsis)
                },
                trailingContent = { RefreshBadge(podcast.refreshStatus) },
            )
        }
    }
}

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
