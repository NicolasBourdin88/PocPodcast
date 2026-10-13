package fr.podcast.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.podcast.shared.api.ApiException
import fr.podcast.shared.home.HomeState
import fr.podcast.shared.model.EpisodeStatus
import fr.podcast.shared.model.Podcast
import fr.podcast.shared.navigation.Destination
import fr.podcast.shared.repository.PodcastRepository
import fr.podcast.ui.account.message
import fr.podcast.ui.components.Artwork
import fr.podcast.ui.home.HomeScreen
import fr.podcast.ui.home.HomeUiState
import fr.podcast.ui.home.loadHome
import fr.podcast.ui.player.MiniPlayer
import fr.podcast.ui.theme.LocalStrings

private val Destination.icon: ImageVector
    get() = when (this) {
        Destination.Home -> Icons.Default.Home
        Destination.Explore -> Icons.Default.Search
        Destination.Library -> Icons.AutoMirrored.Filled.List
        Destination.Settings -> Icons.Default.Settings
    }

/** The app once an account (or the demo) is chosen: Home from [repository], adaptive navigation. */
@Composable
fun MainScaffold(
    repository: PodcastRepository,
    accountLabel: String,
    settings: @Composable (Modifier) -> Unit,
    onSessionExpired: () -> Unit = {},
    initialDestination: Destination = Destination.Home,
) {
    var reloads by remember { mutableIntStateOf(0) }
    val state by produceState<HomeUiState>(HomeUiState.Loading, repository, reloads) {
        value = HomeUiState.Loading
        value = loadHome(repository)
    }
    BoxWithConstraints {
        AdaptiveScaffold(
            layout = WindowLayout.from(maxWidth, maxHeight),
            state = state,
            accountLabel = accountLabel,
            initialDestination = initialDestination,
            settings = settings,
            onRetry = { reloads++ },
            onSessionExpired = onSessionExpired,
        )
    }
}

@Composable
private fun AdaptiveScaffold(
    layout: WindowLayout,
    state: HomeUiState,
    accountLabel: String,
    initialDestination: Destination,
    settings: @Composable (Modifier) -> Unit,
    onRetry: () -> Unit,
    onSessionExpired: () -> Unit,
) {
    val strings = LocalStrings.current
    var selected by rememberSaveable { mutableStateOf(initialDestination) }
    val home = (state as? HomeUiState.Loaded)?.home
    val subscriptions = (state as? HomeUiState.Loaded)?.subscriptions.orEmpty()
    val nowPlaying = home?.resume?.firstOrNull()

    val content: @Composable (Modifier) -> Unit = { modifier ->
        when {
            selected == Destination.Settings -> settings(modifier)
            selected != Destination.Home -> Message(strings.comingSoon(strings.destination(selected)), modifier)
            state is HomeUiState.Loading -> Box(modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            state is HomeUiState.Failed && state.error is ApiException.Unauthorized ->
                Message(strings.sessionExpired, modifier, action = strings.signIn, onAction = onSessionExpired)
            state is HomeUiState.Failed -> Message(state.error.message(strings), modifier, action = strings.retry, onAction = onRetry)
            home != null -> HomeScreen(home, twoColumns = layout == WindowLayout.CompactHeight, modifier = modifier)
        }
    }

    when {
        layout == WindowLayout.Compact -> Scaffold(
            bottomBar = {
                Column {
                    nowPlaying?.let { MiniPlayer(it, singleLine = false) }
                    NavigationBar {
                        Destination.entries.forEach { destination ->
                            NavigationBarItem(
                                selected = selected == destination,
                                onClick = { selected = destination },
                                icon = { Icon(destination.icon, contentDescription = null) },
                                label = { Text(strings.destination(destination)) },
                            )
                        }
                    }
                }
            },
        ) { padding -> content(Modifier.padding(padding)) }

        layout.usesRail -> Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            NavigationRail {
                Destination.entries.forEach { destination ->
                    NavigationRailItem(
                        selected = selected == destination,
                        onClick = { selected = destination },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(strings.destination(destination)) },
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Row(Modifier.weight(1f)) {
                    content(Modifier.weight(1f))
                    if (layout == WindowLayout.Expanded && home != null && selected == Destination.Home) {
                        VerticalDivider()
                        QueuePanel(home, width = 320.dp)
                    }
                }
                nowPlaying?.let { MiniPlayer(it, singleLine = layout == WindowLayout.CompactHeight) }
            }
        }

        else -> PermanentNavigationDrawer(
            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
            drawerContent = {
                PermanentDrawerSheet(Modifier.width(280.dp)) {
                    Text(
                        strings.appName,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 16.dp),
                    )
                    Text(
                        accountLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 28.dp, end = 28.dp, bottom = 16.dp),
                    )
                    Destination.entries.forEach { destination ->
                        NavigationDrawerItem(
                            selected = selected == destination,
                            onClick = { selected = destination },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(strings.destination(destination)) },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                    if (subscriptions.isNotEmpty()) {
                        HorizontalDivider(Modifier.padding(vertical = 12.dp, horizontal = 28.dp))
                        Text(
                            strings.subscriptions,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                        )
                        SubscriptionItems(subscriptions)
                    }
                }
            },
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.weight(1f)) {
                    content(Modifier.weight(1f))
                    if (home != null && selected == Destination.Home) {
                        VerticalDivider()
                        QueuePanel(home, width = 360.dp)
                    }
                }
                nowPlaying?.let { MiniPlayer(it, singleLine = false) }
            }
        }
    }
}

@Composable
private fun SubscriptionItems(subscriptions: List<Podcast>) {
    subscriptions.forEach { podcast ->
        NavigationDrawerItem(
            selected = false,
            onClick = {},
            icon = { Artwork(podcast, size = 28.dp) },
            label = { Text(podcast.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

/** Fake queue: unplayed episodes, in-progress first. */
@Composable
private fun QueuePanel(home: HomeState, width: Dp) {
    val queue = (home.resume + home.latest)
        .distinctBy { it.episode.id }
        .filter { it.episode.status != EpisodeStatus.PLAYED }
    LazyColumn(Modifier.width(width).fillMaxHeight()) {
        item {
            Text(
                LocalStrings.current.queue,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp),
            )
        }
        items(queue, key = { it.episode.id }) { item ->
            ListItem(
                leadingContent = { Artwork(item.podcast, size = 40.dp) },
                headlineContent = { Text(item.episode.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text("${item.podcast.title} · ${LocalStrings.current.duration(item.episode.durationSeconds)}") },
            )
        }
    }
}

@Composable
private fun Message(text: String, modifier: Modifier, action: String? = null, onAction: () -> Unit = {}) {
    Box(modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            action?.let { Button(onClick = onAction) { Text(it) } }
        }
    }
}
