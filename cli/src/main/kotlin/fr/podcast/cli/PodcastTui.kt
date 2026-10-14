package fr.podcast.cli

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.onKeyEvent
import com.jakewharton.mosaic.layout.padding
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import fr.podcast.player.NowPlaying
import fr.podcast.player.PlaybackController
import fr.podcast.player.PlaybackException
import fr.podcast.player.PlayerStatus
import fr.podcast.shared.api.userMessage
import fr.podcast.shared.design.DesignTokens
import fr.podcast.shared.home.EpisodeItem
import fr.podcast.shared.home.HomeState
import fr.podcast.shared.i18n.Strings
import fr.podcast.shared.navigation.Destination

private val Accent = DesignTokens.dark.primary.let { Color(it shr 16 and 0xFF, it shr 8 and 0xFF, it and 0xFF) }

/**
 * Home in the terminal, with the same playback as the apps (ADR 0008): Enter plays the selected
 * episode, space pauses, arrows seek; the numbers are those of `podcast list`.
 * Mosaic runs as long as an effect does: "q" takes the screen, and its collectors, away.
 */
@Composable
fun PodcastTui(state: HomeState, playback: PlaybackController, strings: Strings) {
    var running by remember { mutableStateOf(true) }
    if (running) TuiScreen(state, playback, strings, onQuit = { running = false })
}

@Composable
private fun TuiScreen(state: HomeState, playback: PlaybackController, strings: Strings, onQuit: () -> Unit) {
    val rows = remember(state) { state.numbered }
    var selected by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    val nowPlaying by playback.nowPlaying.collectAsState()
    val queue by playback.queue.collectAsState()

    LaunchedEffect(playback) {
        playback.failures.collect { failure ->
            message = if (failure is PlaybackException) strings.playbackFailed else failure.userMessage(strings)
        }
    }

    Column(
        modifier = Modifier.padding(1).onKeyEvent { event ->
            message = null
            when (event.key) {
                "ArrowUp", "k" -> selected = (selected - 1).coerceAtLeast(0)
                "ArrowDown", "j" -> selected = (selected + 1).coerceAtMost(rows.lastIndex)
                "Enter" -> rows.getOrNull(selected)?.let { playback.play(it.episode.id) }
                "a" -> rows.getOrNull(selected)?.let { playback.enqueue(it.episode.id) }
                " " -> playback.togglePlayPause()
                "ArrowLeft" -> playback.seekBy(-PlaybackController.SKIP_BACK_SECONDS)
                "ArrowRight" -> playback.seekBy(PlaybackController.SKIP_FORWARD_SECONDS)
                "+", "=" -> playback.cycleSpeed()
                "-" -> {
                    val speed = playback.nowPlaying.value?.state?.speed ?: 1f
                    playback.setSpeed(PlaybackController.SPEEDS.lastOrNull { it < speed - 0.01f } ?: PlaybackController.SPEEDS.last())
                }
                "n" -> playback.skipToNext()
                "q", "Escape" -> onQuit()
                else -> return@onKeyEvent false
            }
            true
        },
    ) {
        Text("${strings.appName} · ${strings.destination(Destination.Home)}", textStyle = TextStyle.Bold, color = Accent)
        Text("")

        if (state.resume.isNotEmpty()) {
            Text(strings.resumeListening, textStyle = TextStyle.Bold)
            state.resume.forEachIndexed { index, item ->
                EpisodeLine(index, item, selected == index, nowPlaying)
                Row {
                    Text("      ${progressBar(item.episode.progress)} ", color = Accent)
                    Text("${strings.remaining(item.episode.remainingMinutes)} · ${item.podcast.title}", textStyle = TextStyle.Dim)
                }
            }
            Text("")
        }

        Text(strings.latestEpisodes, textStyle = TextStyle.Bold)
        state.latest.forEachIndexed { offset, item ->
            val index = state.resume.size + offset
            EpisodeLine(index, item, selected == index, nowPlaying)
            Text(
                "      ${item.podcast.title} · ${strings.shortDate(item.episode.publishedOn())} · ${strings.duration(item.episode.durationSeconds)} · ${strings.status(item.episode)}",
                textStyle = TextStyle.Dim,
            )
        }

        Text("")
        Text(
            if (queue.isEmpty()) strings.emptyQueue else "${strings.queue} : " + queue.joinToString(" · ") { it.episode.title },
            textStyle = TextStyle.Dim,
        )
        nowPlaying?.let { PlayerLines(it, strings) }
        message?.let { Text(it, color = Color.Red) }
        Text("")
        Text(strings.tuiHelp, textStyle = TextStyle.Dim)
        Text(strings.tuiPlayerHelp, textStyle = TextStyle.Dim)
    }
}

@Composable
private fun EpisodeLine(index: Int, item: EpisodeItem, selected: Boolean, nowPlaying: NowPlaying?) {
    val playing = nowPlaying?.item?.episode?.id == item.episode.id
    Text(
        "${if (selected) "›" else " "} ${"${index + 1}.".padStart(3)} ${if (playing) "♪ " else ""}${item.episode.title}",
        textStyle = if (selected) TextStyle.Invert else TextStyle.Unspecified,
    )
}

/** The episode being played: its state, a progress bar, the position and the speed. */
@Composable
private fun PlayerLines(nowPlaying: NowPlaying, strings: Strings) {
    val state = nowPlaying.state
    val icon = when {
        state.status == PlayerStatus.ERROR -> "⚠"
        state.status == PlayerStatus.ENDED -> "■"
        state.status == PlayerStatus.LOADING || state.buffering -> "…"
        state.playing -> "▶"
        else -> "⏸"
    }
    Text("")
    Text("$icon ${nowPlaying.item.episode.title} — ${nowPlaying.item.podcast.title}", textStyle = TextStyle.Bold)
    Row {
        Text("  ${progressBar(nowPlaying.progress, width = 30)} ", color = Accent)
        Text(
            "${strings.clock(state.positionSeconds.toInt())} / ${strings.clock(nowPlaying.durationSeconds.toInt())} · ${strings.speed(state.speed)}",
        )
    }
}
