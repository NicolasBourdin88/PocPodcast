package fr.podcast.cli

import fr.podcast.shared.home.EpisodeItem
import fr.podcast.shared.home.HomeState
import fr.podcast.shared.i18n.Strings
import kotlin.math.roundToInt

/** Progress bar drawn with block characters, e.g. "████░░░░░░". */
fun progressBar(progress: Float, width: Int = 20): String {
    val filled = (progress * width).roundToInt().coerceIn(0, width)
    return "█".repeat(filled) + "░".repeat(width - filled)
}

/** The episodes of Home in the order shown, numbered from 1: `play` and `queue add` use the numbers. */
val HomeState.numbered: List<EpisodeItem> get() = resume + latest

/** Plain-text Home screen for `podcast list`. */
fun renderHomeText(state: HomeState, strings: Strings): String = buildString {
    var number = 0
    if (state.resume.isNotEmpty()) {
        appendLine(strings.resumeListening)
        state.resume.forEach { item ->
            appendLine("${label(++number)} ${item.episode.title} — ${item.podcast.title}")
            appendLine("    ${progressBar(item.episode.progress)} ${strings.remaining(item.episode.remainingMinutes)}")
        }
        appendLine()
    }
    appendLine(strings.latestEpisodes)
    state.latest.forEach { item ->
        val episode = item.episode
        appendLine("${label(++number)} ${strings.shortDate(episode.publishedOn()).padEnd(8)} ${episode.title}")
        appendLine("    ${" ".repeat(8)} ${item.podcast.title} · ${strings.duration(episode.durationSeconds)} · ${(listOf(strings.status(episode)) + strings.marks(episode)).joinToString(" · ")}")
    }
}.trimEnd()

private fun label(number: Int) = "$number.".padStart(3)
