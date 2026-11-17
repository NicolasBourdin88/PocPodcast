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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import fr.podcast.shared.api.userMessage
import fr.podcast.shared.model.Podcast
import fr.podcast.shared.navigation.Destination
import fr.podcast.shared.repository.PodcastClient
import fr.podcast.ui.components.Artwork
import fr.podcast.ui.components.PodcastIcons
import fr.podcast.ui.playlists.PlaylistsScreen
import fr.podcast.ui.explore.ExploreScreen
import fr.podcast.ui.home.HomeScreen
import fr.podcast.ui.home.HomeUiState
import fr.podcast.ui.home.loadHome
import fr.podcast.ui.library.LibraryScreen
import fr.podcast.player.PlaybackException
import fr.podcast.player.PlayerStatus
import fr.podcast.shared.home.EpisodeItem
import fr.podcast.ui.player.LocalPlayback
import fr.podcast.ui.player.MiniPlayer
import fr.podcast.ui.player.PlayerBar
import fr.podcast.ui.player.PlayerScreen
import fr.podcast.ui.player.queueItems
import fr.podcast.ui.theme.LocalStrings
import kotlinx.coroutines.delay

private val Destination.icon: ImageVector
    get() = when (this) {
        Destination.Home -> Icons.Default.Home
        Destination.Explore -> Icons.Default.Search
        Destination.Library -> Icons.AutoMirrored.Filled.List
        Destination.Playlists -> PodcastIcons.Playlist
        Destination.Settings -> Icons.Default.Settings
    }

/** The app once an account (or the demo) is chosen: everything comes from [client], adaptive navigation. */
@Composable
fun MainScaffold(
    client: PodcastClient,
    accountLabel: String,
    settings: @Composable (Modifier) -> Unit,
    onSessionExpired: () -> Unit = {},
    initialDestination: Destination = Destination.Home,
    initialPodcastId: String? = null,
    initialQuery: String = "",
    initialPlayerOpen: Boolean = false,
    initialPlaylistId: String? = null,
) {
    var reloads by remember { mutableIntStateOf(0) }
    val state by produceState<HomeUiState>(HomeUiState.Loading, client, reloads) {
        // A reload after a library change keeps the current Home on screen until the new one is ready.
        if (value !is HomeUiState.Loaded) value = HomeUiState.Loading
        value = loadHome(client)
    }
    // Home follows playback: another episode, a pause or an end changes "Continue listening".
    val nowPlaying by LocalPlayback.current.nowPlaying.collectAsState()
    val playbackKey = nowPlaying?.item?.episode?.id to (nowPlaying?.state?.playing == true || nowPlaying?.state?.status == PlayerStatus.ENDED)
    LaunchedEffect(playbackKey) {
        if (playbackKey.first == null) return@LaunchedEffect
        delay(1_500) // once the position has reached the server
        reloads++
    }
    BoxWithConstraints {
        AdaptiveScaffold(
            layout = WindowLayout.from(maxWidth, maxHeight),
            client = client,
            state = state,
            accountLabel = accountLabel,
            initialDestination = initialDestination,
            initialPodcastId = initialPodcastId,
            initialQuery = initialQuery,
            initialPlayerOpen = initialPlayerOpen,
            initialPlaylistId = initialPlaylistId,
            settings = settings,
            onReload = { reloads++ },
            onSessionExpired = onSessionExpired,
        )
    }
}

@Composable
private fun AdaptiveScaffold(
    layout: WindowLayout,
    client: PodcastClient,
    state: HomeUiState,
    accountLabel: String,
    initialDestination: Destination,
    initialPodcastId: String?,
    initialQuery: String,
    initialPlayerOpen: Boolean,
    initialPlaylistId: String?,
    settings: @Composable (Modifier) -> Unit,
    onReload: () -> Unit,
    onSessionExpired: () -> Unit,
) {
    val strings = LocalStrings.current
    var selected by rememberSaveable { mutableStateOf(initialDestination) }
    var openPodcastId by rememberSaveable { mutableStateOf(initialPodcastId) }
    var openPlaylistId by rememberSaveable { mutableStateOf(initialPlaylistId) }

    // Choosing Library or Playlists again goes back to the list.
    fun select(destination: Destination) {
        if (destination == selected && destination == Destination.Library) openPodcastId = null
        if (destination == selected && destination == Destination.Playlists) openPlaylistId = null
        selected = destination
    }
    val home = (state as? HomeUiState.Loaded)?.home
    val subscriptions = (state as? HomeUiState.Loaded)?.subscriptions.orEmpty()

    val playback = LocalPlayback.current
    val nowPlaying by playback.nowPlaying.collectAsState()
    val queue by playback.queue.collectAsState()
    // The episode playing, or else the one to resume.
    val playerItem = nowPlaying?.item ?: home?.resume?.firstOrNull()
    var playerOpen by rememberSaveable { mutableStateOf(initialPlayerOpen) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(playback) {
        playback.failures.collect { failure ->
            snackbar.showSnackbar(if (failure is PlaybackException) strings.playbackFailed else failure.userMessage(strings))
        }
    }

    val content: @Composable (Modifier) -> Unit = { modifier ->
        when {
            selected == Destination.Settings -> settings(modifier)
            selected == Destination.Explore -> ExploreScreen(client, onSubscribed = onReload, modifier, initialQuery)
            selected == Destination.Playlists -> PlaylistsScreen(
                client = client,
                openPlaylistId = openPlaylistId,
                onOpen = { openPlaylistId = it },
                twoPanes = layout == WindowLayout.Expanded || layout == WindowLayout.Large,
                modifier = modifier,
            )
            selected == Destination.Library -> LibraryScreen(
                client = client,
                openPodcastId = openPodcastId,
                onOpenPodcast = { openPodcastId = it },
                twoPanes = layout == WindowLayout.Expanded || layout == WindowLayout.Large,
                onChanged = onReload,
                modifier = modifier,
            )
            state is HomeUiState.Loading -> Box(modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            state is HomeUiState.Failed && state.error is ApiException.Unauthorized ->
                Message(strings.sessionExpired, modifier, action = strings.signIn, onAction = onSessionExpired)
            state is HomeUiState.Failed -> Message(state.error.userMessage(strings), modifier, action = strings.retry, onAction = onReload)
            home != null -> HomeScreen(home, twoColumns = layout == WindowLayout.CompactHeight, modifier = modifier)
        }
    }

    Box(Modifier.fillMaxSize()) {
        when {
            layout == WindowLayout.Compact -> Scaffold(
                bottomBar = {
                    Column {
                        playerItem?.let { MiniPlayer(it, nowPlaying, singleLine = false, onOpen = { playerOpen = true }) }
                        NavigationBar {
                            Destination.entries.forEach { destination ->
                                NavigationBarItem(
                                    selected = selected == destination,
                                    onClick = { select(destination) },
                                    icon = { Icon(destination.icon, contentDescription = null) },
                                    // Five destinations on a phone: one line each, centered; a long one ("Bibliothèque")
                                    // keeps its width and runs into its neighbors' margins, which have room.
                                    label = {
                                        Text(
                                            strings.destination(destination),
                                            maxLines = 1,
                                            softWrap = false,
                                            modifier = Modifier.wrapContentWidth(unbounded = true),
                                        )
                                    },
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
                            onClick = { select(destination) },
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
                            QueuePanel(queue, width = 320.dp)
                        }
                    }
                    playerItem?.let { item ->
                        if (layout == WindowLayout.Expanded) {
                            PlayerBar(item, nowPlaying, onOpen = { playerOpen = true })
                        } else {
                            MiniPlayer(item, nowPlaying, singleLine = layout == WindowLayout.CompactHeight, onOpen = { playerOpen = true })
                        }
                    }
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
                                onClick = { select(destination) },
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
                            SubscriptionItems(subscriptions, selectedId = openPodcastId.takeIf { selected == Destination.Library }) {
                                openPodcastId = it.id
                                selected = Destination.Library
                            }
                        }
                    }
                },
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.weight(1f)) {
                        content(Modifier.weight(1f))
                        if (home != null && selected == Destination.Home) {
                            VerticalDivider()
                            QueuePanel(queue, width = 360.dp)
                        }
                    }
                    playerItem?.let { PlayerBar(it, nowPlaying, onOpen = { playerOpen = true }) }
                }
            }
        }
        if (playerOpen && playerItem != null) {
            PlayerScreen(
                client = client,
                item = playerItem,
                nowPlaying = nowPlaying,
                twoColumns = layout != WindowLayout.Compact && layout != WindowLayout.Medium,
                onClose = { playerOpen = false },
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
    }
}

@Composable
private fun SubscriptionItems(subscriptions: List<Podcast>, selectedId: String?, onOpen: (Podcast) -> Unit) {
    subscriptions.forEach { podcast ->
        NavigationDrawerItem(
            selected = podcast.id == selectedId,
            onClick = { onOpen(podcast) },
            icon = { Artwork(podcast, size = 28.dp) },
            label = { Text(podcast.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

/** The queue beside Home on wide windows. */
@Composable
private fun QueuePanel(queue: List<EpisodeItem>, width: Dp) {
    val playback = LocalPlayback.current
    val strings = LocalStrings.current
    LazyColumn(Modifier.width(width).fillMaxHeight()) { queueItems(queue, playback, strings) }
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
