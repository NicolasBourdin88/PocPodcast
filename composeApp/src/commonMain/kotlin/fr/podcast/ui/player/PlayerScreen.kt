package fr.podcast.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.podcast.player.NowPlaying
import fr.podcast.shared.home.EpisodeItem
import fr.podcast.ui.components.Artwork
import fr.podcast.ui.theme.LocalStrings
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import fr.podcast.shared.repository.PodcastClient
import fr.podcast.ui.components.FavoriteButton
import fr.podcast.ui.components.StarRating
import fr.podcast.ui.components.attempt
import kotlinx.coroutines.launch
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState

internal const val SKIP_BACK = fr.podcast.player.PlaybackController.SKIP_BACK_SECONDS
internal const val SKIP_FORWARD = fr.podcast.player.PlaybackController.SKIP_FORWARD_SECONDS

/**
 * The player in a sheet (ADR 0013): it slides up from the bottom over everything, as the
 * platforms' own players do, and closes when dragged down, with the back gesture or its chevron.
 */
@Composable
fun PlayerSheet(client: PodcastClient, item: EpisodeItem, nowPlaying: NowPlaying?, twoColumns: Boolean, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // The whole width on tablets and desktops too: the player takes the window.
        sheetMaxWidth = Dp.Unspecified,
    ) {
        PlayerScreen(
            client = client,
            item = item,
            nowPlaying = nowPlaying,
            twoColumns = twoColumns,
            // The chevron slides the sheet down as a drag does.
            onClose = { scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) onDismiss() } },
        )
    }
}

/**
 * The player's content. One column on narrow windows (controls, then the queue); two on wide or
 * landscape ones (controls beside the queue).
 */
@Composable
fun PlayerScreen(
    client: PodcastClient,
    item: EpisodeItem,
    nowPlaying: NowPlaying?,
    twoColumns: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val playback = LocalPlayback.current
    val queue by playback.queue.collectAsState()

    // The sheet gives the surface, the window insets and the back gesture.
    Column(modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            IconButton(onClick = onClose) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = strings.close) }
            Text(strings.nowPlaying, style = MaterialTheme.typography.titleMedium)
        }
        if (twoColumns) {
            Row(Modifier.fillMaxSize()) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                    // A phone in landscape has no room for the artwork: the controls come first.
                    val artwork = if (maxHeight < 420.dp) null else minOf(maxHeight * 0.45f, maxWidth * 0.6f, 280.dp)
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        NowPlayingPane(client, item, nowPlaying, artwork, queueEmpty = queue.isEmpty())
                    }
                }
                VerticalDivider()
                LazyColumn(Modifier.weight(1f).fillMaxSize()) { queueItems(queue, playback, strings) }
            }
        } else {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val artworkSize = minOf(maxWidth * 0.7f, 300.dp)
                LazyColumn(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    item(key = "now-playing") { NowPlayingPane(client, item, nowPlaying, artworkSize, queueEmpty = queue.isEmpty()) }
                    queueItems(queue, playback, strings)
                }
            }
        }
    }
}

@Composable
private fun NowPlayingPane(client: PodcastClient, item: EpisodeItem, nowPlaying: NowPlaying?, artworkSize: Dp?, queueEmpty: Boolean) {
    val playback = LocalPlayback.current
    val state = nowPlaying?.state
    val scope = rememberCoroutineScope()
    // The user's marks, changed at once on screen; put back if the server refuses (ADR 0010).
    var favorite by remember(item.episode.id) { mutableStateOf(item.episode.favorite) }
    var rating by remember(item.episode.id) { mutableStateOf(item.episode.rating) }
    Column(
        Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        artworkSize?.let {
            Artwork(item.podcast, size = it)
            Spacer(Modifier.height(4.dp))
        }
        Text(
            item.episode.title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Text(item.podcast.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            FavoriteButton(favorite) { value ->
                favorite = value
                scope.launch { attempt { client.setFavorite(item.episode.id, value) }.onFailure { favorite = !value } }
            }
            val previous = rating
            StarRating(rating, starSize = 20.dp, onRate = { value ->
                rating = value
                scope.launch { attempt { client.rate(item.episode.id, value) }.onFailure { rating = previous } }
            })
        }
        PositionSlider(
            positionSeconds = state?.positionSeconds ?: item.episode.positionSeconds.toDouble(),
            durationSeconds = nowPlaying?.durationSeconds ?: item.episode.durationSeconds.toDouble(),
            onSeek = playback::seekTo,
            enabled = nowPlaying != null,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SkipButton(forward = false, onClick = { playback.seekBy(-SKIP_BACK) }, size = 56.dp, enabled = nowPlaying != null)
            PlayPauseButton(
                playing = state?.playing == true,
                buffering = state?.buffering == true,
                onClick = { if (nowPlaying == null) playback.play(item.episode.id) else playback.togglePlayPause() },
                size = 72.dp,
            )
            SkipButton(forward = true, onClick = { playback.seekBy(SKIP_FORWARD) }, size = 56.dp, enabled = nowPlaying != null)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            SpeedButton(speed = state?.speed ?: 1f, onClick = playback::cycleSpeed)
            Spacer(Modifier.width(24.dp))
            NextButton(enabled = !queueEmpty, onClick = playback::skipToNext)
        }
    }
}
