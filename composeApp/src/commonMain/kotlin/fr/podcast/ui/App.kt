package fr.podcast.ui

import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.podcast.shared.home.HomeState
import fr.podcast.shared.home.LoadHomeState
import fr.podcast.shared.model.EpisodeStatus
import fr.podcast.shared.model.Podcast
import fr.podcast.shared.repository.FakePodcastRepository
import fr.podcast.shared.repository.PodcastRepository
import fr.podcast.ui.components.Artwork
import fr.podcast.ui.home.HomeScreen
import fr.podcast.ui.player.MiniPlayer

enum class Destination(val label: String, val icon: ImageVector) {
    Home("Accueil", Icons.Default.Home),
    Explore("Explorer", Icons.Default.Search),
    Library("Bibliothèque", Icons.AutoMirrored.Filled.List),
    Settings("Réglages", Icons.Default.Settings),
}

@Composable
fun App(repository: PodcastRepository = remember { FakePodcastRepository() }) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        val home by produceState<HomeState?>(null, repository) { value = LoadHomeState(repository)() }
        val subscriptions by produceState(emptyList<Podcast>(), repository) { value = repository.subscriptions() }

        Surface(Modifier.fillMaxSize()) {
            BoxWithConstraints {
                AppScaffold(WindowLayout.from(maxWidth, maxHeight), home, subscriptions)
            }
        }
    }
}

@Composable
private fun AppScaffold(layout: WindowLayout, home: HomeState?, subscriptions: List<Podcast>) {
    var selected by rememberSaveable { mutableStateOf(Destination.Home) }
    val nowPlaying = home?.resume?.firstOrNull()

    val content: @Composable (Modifier) -> Unit = { modifier ->
        when {
            selected != Destination.Home -> Placeholder(selected.label, modifier)
            home == null -> Box(modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            else -> HomeScreen(home, twoColumns = layout == WindowLayout.CompactHeight, modifier = modifier)
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
                                label = { Text(destination.label) },
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
                        label = { Text(destination.label) },
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Row(Modifier.weight(1f)) {
                    content(Modifier.weight(1f))
                    if (layout == WindowLayout.Expanded && home != null) {
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
                        "Podcast",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
                    )
                    Destination.entries.forEach { destination ->
                        NavigationDrawerItem(
                            selected = selected == destination,
                            onClick = { selected = destination },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(destination.label) },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 12.dp, horizontal = 28.dp))
                    Text(
                        "Abonnements",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                    )
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
            },
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.weight(1f)) {
                    content(Modifier.weight(1f))
                    if (home != null) {
                        VerticalDivider()
                        QueuePanel(home, width = 360.dp)
                    }
                }
                nowPlaying?.let { MiniPlayer(it, singleLine = false) }
            }
        }
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
                "File d'attente",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp),
            )
        }
        items(queue, key = { it.episode.id }) { item ->
            ListItem(
                leadingContent = { Artwork(item.podcast, size = 40.dp) },
                headlineContent = { Text(item.episode.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text("${item.podcast.title} · ${item.episode.durationLabel}") },
            )
        }
    }
}

@Composable
private fun Placeholder(label: String, modifier: Modifier) {
    Box(modifier.fillMaxSize(), Alignment.Center) {
        Text("$label — à venir", style = MaterialTheme.typography.bodyLarge)
    }
}
