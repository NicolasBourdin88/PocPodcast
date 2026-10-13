package fr.podcast.cli

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import fr.podcast.shared.design.DesignTokens
import fr.podcast.shared.home.HomeState
import fr.podcast.shared.i18n.Strings
import fr.podcast.shared.navigation.Destination
import kotlinx.coroutines.CompletableDeferred

private val Accent = DesignTokens.dark.primary.let { Color(it shr 16 and 0xFF, it shr 8 and 0xFF, it and 0xFF) }

/** Interactive Home screen: same state as every other client, rendered in the terminal. */
@Composable
fun HomeTui(state: HomeState, strings: Strings) {
    val quit = remember { CompletableDeferred<Unit>() }
    var selected by remember { mutableIntStateOf(0) }

    // Mosaic keeps running while an effect is active: wait for "q".
    LaunchedEffect(Unit) { quit.await() }

    Column(
        modifier = Modifier.padding(1).onKeyEvent { event ->
            when (event.key) {
                "ArrowUp", "k" -> selected = (selected - 1).coerceAtLeast(0)
                "ArrowDown", "j" -> selected = (selected + 1).coerceAtMost(state.latest.lastIndex)
                "q", "Escape" -> quit.complete(Unit)
                else -> return@onKeyEvent false
            }
            true
        },
    ) {
        Text("${strings.appName} · ${strings.destination(Destination.Home)}", textStyle = TextStyle.Bold, color = Accent)
        Text("")

        if (state.resume.isNotEmpty()) {
            Text(strings.resumeListening, textStyle = TextStyle.Bold)
            state.resume.forEach { item ->
                Text("  ${item.episode.title}")
                Row {
                    Text("  ${progressBar(item.episode.progress)} ", color = Accent)
                    Text("${strings.remaining(item.episode.remainingMinutes)} · ${item.podcast.title}", textStyle = TextStyle.Dim)
                }
            }
            Text("")
        }

        Text(strings.latestEpisodes, textStyle = TextStyle.Bold)
        state.latest.forEachIndexed { index, item ->
            val isSelected = index == selected
            Text(
                "${if (isSelected) "›" else " "} ${item.episode.title}",
                textStyle = if (isSelected) TextStyle.Invert else TextStyle.Unspecified,
            )
            Text("    ${item.podcast.title} · ${strings.shortDate(item.episode.publishedOn)} · ${strings.duration(item.episode.durationSeconds)} · ${strings.status(item.episode)}", textStyle = TextStyle.Dim)
        }

        Text("")
        Text(strings.tuiHelp, textStyle = TextStyle.Dim)
    }
}
