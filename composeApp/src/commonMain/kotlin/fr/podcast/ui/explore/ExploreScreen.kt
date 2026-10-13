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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(strings.searchPlaceholder) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                    )
                    Button(onClick = ::runSearch, enabled = query.trim().length >= 2) { Text(strings.search) }
                }
            }
            when (val state = search) {
                SearchState.Idle -> Unit
                SearchState.Searching -> item(key = "searching") {
                    Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { CircularProgressIndicator() }
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
            item(key = "add-feed") { AddFeed(library, onSubscribed) }
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
                busy -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else -> FilledTonalButton(onClick = onSubscribe) { Text(strings.subscribe) }
            }
        },
    )
}

/** Subscription by address, for feeds the directories do not know. */
@Composable
private fun AddFeed(library: LibraryRepository, onSubscribed: () -> Unit) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var feedUrl by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // message, is an error

    fun add() {
        val address = feedUrl.trim()
        if (address.isEmpty() || busy) return
        scope.launch {
            busy = true
            result = attempt { library.subscribe(address) }.fold(
                onSuccess = {
                    feedUrl = ""
                    onSubscribed()
                    strings.subscribedTo(it.podcast.title) to false
                },
                onFailure = { it.userMessage(strings) to true },
            )
            busy = false
        }
    }

    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(strings.addFeedTitle, style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = feedUrl,
                onValueChange = { feedUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(strings.feedUrl) },
                placeholder = { Text("https://…/feed.xml") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = ::add, enabled = feedUrl.isNotBlank() && !busy) { Text(strings.subscribe) }
                if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            result?.let { (message, error) ->
                Text(message, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun Notice(text: String, error: Boolean = false) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The placeholder artwork is keyed by the feed, until the podcast has its own id. */
private fun SearchResult.asPodcast() = Podcast(id = feedUrl, title = title, author = author, feedUrl = feedUrl, artworkUrl = artworkUrl)
