package fr.podcast.ui.explore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.podcast.shared.api.userMessage
import fr.podcast.shared.model.Podcast
import fr.podcast.shared.model.SearchResult
import fr.podcast.shared.repository.LibraryRepository
import fr.podcast.ui.components.Artwork
import fr.podcast.ui.components.attempt
import fr.podcast.ui.theme.LocalStrings
import kotlinx.coroutines.launch
import fr.podcast.ui.components.AppSearchField
import fr.podcast.ui.components.AppTextField
import fr.podcast.ui.components.FormDialog
import fr.podcast.ui.components.Loading
import fr.podcast.ui.components.SmallLoading
import fr.podcast.shared.model.SubscriptionItem
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width

private sealed interface SearchState {
    data object Idle : SearchState
    data object Searching : SearchState
    data object Unavailable : SearchState
    data class Results(val query: String, val results: List<SearchResult>) : SearchState
    data class Failed(val message: String) : SearchState
}

/**
 * Explore: the server's directory search (Apple Podcasts, Podcast Index) and subscription by
 * feed address. [onSubscribed] lets the rest of the app reload the subscriptions.
 */
@Composable
fun ExploreScreen(
    library: LibraryRepository,
    onSubscribed: () -> Unit,
    modifier: Modifier = Modifier,
    initialQuery: String = "",
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var search by remember { mutableStateOf<SearchState>(SearchState.Idle) }
    // Per result: being subscribed, or why it was refused.
    var subscribing by remember { mutableStateOf(emptySet<String>()) }
    var refusals by remember { mutableStateOf(emptyMap<String, String>()) }
    // The podcast just subscribed to by its address.
    var added by remember { mutableStateOf<String?>(null) }

    fun runSearch() {
        val text = query.trim()
        if (text.length < 2) return
        scope.launch {
            search = SearchState.Searching
            search = attempt { library.search(text) }.fold(
                onSuccess = { results -> results?.let { SearchState.Results(text, it) } ?: SearchState.Unavailable },
                onFailure = { SearchState.Failed(it.userMessage(strings)) },
            )
        }
    }

    fun subscribe(result: SearchResult) {
        scope.launch {
            subscribing += result.feedUrl
            attempt { library.subscribe(result.feedUrl) }
                .onSuccess {
                    val current = search
                    if (current is SearchState.Results) {
                        search = current.copy(results = current.results.map { if (it == result) it.copy(subscribed = true) else it })
                    }
                    onSubscribed()
                }
                .onFailure { refusals += result.feedUrl to it.userMessage(strings) }
            subscribing -= result.feedUrl
        }
    }

    LaunchedEffect(Unit) { if (initialQuery.isNotBlank()) runSearch() }

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 760.dp).fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            item(key = "search") {
                // The keyboard's search key runs the search (ADR 0013).
                AppSearchField(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = strings.searchPlaceholder,
                    onSearch = { runSearch() },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item(key = "add-feed") {
                AddFeedButton(library) { item ->
                    added = item.podcast.title
                    onSubscribed()
                }
            }
            added?.let { title -> item(key = "added") { Notice(strings.subscribedTo(title), success = true) } }
            when (val state = search) {
                SearchState.Idle -> Unit
                SearchState.Searching -> item(key = "searching") {
                    Loading(Modifier.fillMaxWidth().padding(24.dp))
                }
                SearchState.Unavailable -> item(key = "unavailable") { Notice(strings.searchUnavailable) }
                is SearchState.Failed -> item(key = "failed") { Notice(state.message, error = true) }
                is SearchState.Results -> {
                    if (state.results.isEmpty()) item(key = "empty") { Notice(strings.noResults(state.query)) }
                    items(state.results, key = { "result-${it.feedUrl}" }) { result ->
                        ResultRow(
                            result = result,
                            busy = result.feedUrl in subscribing,
                            refusal = refusals[result.feedUrl],
                            onSubscribe = { subscribe(result) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(result: SearchResult, busy: Boolean, refusal: String?, onSubscribe: () -> Unit) {
    val strings = LocalStrings.current
    ListItem(
        leadingContent = { Artwork(result.asPodcast(), size = 56.dp) },
        overlineContent = { Text(result.source) },
        headlineContent = { Text(result.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            if (refusal != null) {
                Text(refusal, color = MaterialTheme.colorScheme.error)
            } else {
                Text(result.author, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        trailingContent = {
            when {
                result.subscribed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(strings.subscribed, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                busy -> SmallLoading()
                else -> FilledTonalButton(onClick = onSubscribe) { Text(strings.subscribe) }
            }
        },
    )
}

/** Subscription by address, for feeds the directories do not know: a button, then a dialog. */
@Composable
private fun AddFeedButton(library: LibraryRepository, onSubscribed: (SubscriptionItem) -> Unit) {
    val strings = LocalStrings.current
    var open by rememberSaveable { mutableStateOf(false) }
    FilledTonalButton(onClick = { open = true }, modifier = Modifier.padding(horizontal = 16.dp)) {
        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(strings.addFeedTitle)
    }
    if (open) {
        var feedUrl by rememberSaveable { mutableStateOf("") }
        FormDialog(
            title = strings.addFeedTitle,
            confirmLabel = strings.subscribe,
            onDismiss = { open = false },
            canConfirm = feedUrl.isNotBlank(),
            onConfirm = { onSubscribed(library.subscribe(feedUrl.trim())) },
        ) {
            AppTextField(
                value = feedUrl,
                onValueChange = { feedUrl = it },
                label = strings.feedUrl,
                placeholder = "https://…/feed.xml",
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Notice(text: String, error: Boolean = false, success: Boolean = false) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        color = when {
            error -> MaterialTheme.colorScheme.error
            success -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

/** The placeholder artwork is keyed by the feed, until the podcast has its own id. */
private fun SearchResult.asPodcast() = Podcast(id = feedUrl, title = title, author = author, feedUrl = feedUrl, artworkUrl = artworkUrl)
