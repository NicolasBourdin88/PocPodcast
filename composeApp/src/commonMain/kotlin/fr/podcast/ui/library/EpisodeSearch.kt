package fr.podcast.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.podcast.shared.api.userMessage
import fr.podcast.shared.home.EpisodeItem
import fr.podcast.shared.model.EpisodePage
import fr.podcast.shared.model.EpisodeQuery
import fr.podcast.shared.model.EpisodeStatus
import fr.podcast.shared.model.SubscriptionItem
import fr.podcast.shared.model.TagRules
import fr.podcast.shared.repository.PodcastClient
import fr.podcast.ui.components.Artwork
import fr.podcast.ui.components.attempt
import fr.podcast.ui.player.EpisodePlayButton
import fr.podcast.ui.theme.LocalStrings
import kotlinx.coroutines.delay
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn
import kotlin.time.Clock
import fr.podcast.ui.components.AppSearchField
import androidx.compose.foundation.layout.Column
import fr.podcast.ui.components.Loading

/** The longest results list shown; the count says how many match in all. */
private const val MAX_RESULTS = 100

/**
 * The search of the user's episodes (ADR 0012): words, then a chip per criterion. Each change is
 * sent to the server a moment after the last key.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EpisodeSearch(client: PodcastClient, subscriptions: List<SubscriptionItem>, modifier: Modifier = Modifier, initialText: String = "") {
    val strings = LocalStrings.current
    var text by rememberSaveable { mutableStateOf(initialText) }
    var status by remember { mutableStateOf<EpisodeStatus?>(null) }
    var favoritesOnly by remember { mutableStateOf(false) }
    var minRating by remember { mutableIntStateOf(0) }
    var days by remember { mutableStateOf<Int?>(null) }
    var podcastId by remember { mutableStateOf<String?>(null) }
    var tag by remember { mutableStateOf<String?>(null) }
    val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }
    val query = EpisodeQuery(
        text = text,
        status = status,
        favoritesOnly = favoritesOnly,
        minRating = minRating,
        from = days?.let { today.minus(it, DateTimeUnit.DAY) },
        podcastId = podcastId,
        tag = tag,
    )
    var result by remember(client) { mutableStateOf<Result<EpisodePage>?>(null) }
    LaunchedEffect(client, query) {
        // The first search at once; then a moment after the last change.
        if (result != null) delay(300)
        result = attempt { client.searchEpisodes(query, limit = MAX_RESULTS) }
    }
    val tags = TagRules.sorted(subscriptions.flatMap { it.subscription.tags })

    LazyColumn(modifier) {
        item(key = "text") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                AppSearchField(query = text, onQueryChange = { text = it }, placeholder = strings.searchEpisodes, modifier = Modifier.fillMaxWidth())
                Text(
                    strings.searchEpisodesHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                )
            }
        }
        item(key = "filters") {
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip(strings.statusLabel, status, listOf(null to strings.anyStatus) + EpisodeStatus.entries.map { it to strings.statusFilter(it) }) { status = it }
                FilterChip(selected = favoritesOnly, onClick = { favoritesOnly = !favoritesOnly }, label = { Text(strings.favoritesOnly) })
                ChoiceChip(strings.minRating, minRating, (0..5).map { it to if (it == 0) strings.anyRating else strings.stars(it) }) { minRating = it }
                ChoiceChip(strings.period, days, listOf(null to strings.anyDate) + listOf(7, 30, 365).map { it to strings.lastDays(it) }) { days = it }
                ChoiceChip(strings.podcastLabel, podcastId, listOf(null to strings.anyPodcast) + subscriptions.map { it.podcast.id to it.podcast.title }) { podcastId = it }
                if (tags.isNotEmpty()) {
                    ChoiceChip(strings.tags, tag, listOf(null to strings.allTags) + tags.map { it to it }) { tag = it }
                }
            }
        }
        when (val current = result) {
            null -> item(key = "loading") { Loading(Modifier.fillMaxWidth().padding(24.dp)) }
            else -> current.fold(
                onSuccess = { page ->
                    item(key = "count") {
                        Text(
                            strings.episodeCount(page.total),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (page.items.isEmpty()) {
                        item(key = "none") { Text(strings.noEpisodeFound, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    items(page.items, key = { it.episode.id }) { SearchResult(it) }
                },
                onFailure = { error ->
                    item(key = "error") { Text(error.userMessage(strings), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
                },
            )
        }
    }
}

/** A chip opening its choices; it shows the choice once one is made, its [label] otherwise. */
@Composable
private fun <T> ChoiceChip(label: String, selected: T, options: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val default = options.first().first
    Box {
        FilterChip(
            selected = selected != default,
            onClick = { open = true },
            label = {
                Text(
                    if (selected == default) label else options.firstOrNull { it.first == selected }?.second ?: label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        onSelect(value)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SearchResult(item: EpisodeItem) {
    val strings = LocalStrings.current
    val episode = item.episode
    ListItem(
        leadingContent = { Artwork(item.podcast, size = 48.dp) },
        overlineContent = { Text("${item.podcast.title} · ${strings.shortDate(episode.publishedOn())}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        headlineContent = { Text(episode.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text((listOf(strings.duration(episode.durationSeconds), strings.status(episode)) + strings.marks(episode)).joinToString(" · "))
        },
        trailingContent = { EpisodePlayButton(episode.id) },
    )
}
