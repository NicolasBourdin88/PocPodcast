package fr.podcast.cli

import fr.podcast.shared.home.EpisodeItem
import fr.podcast.shared.home.HomeState
import fr.podcast.shared.model.EpisodeStatus
import kotlin.math.roundToInt

/** Progress bar drawn with block characters, e.g. "████░░░░░░". */
fun progressBar(progress: Float, width: Int = 20): String {
    val filled = (progress * width).roundToInt().coerceIn(0, width)
    return "█".repeat(filled) + "░".repeat(width - filled)
}

fun EpisodeItem.statusLabel(): String = when (episode.status) {
    EpisodeStatus.NEW -> "Nouveau"
    EpisodeStatus.IN_PROGRESS -> "${episode.remainingMinutes} min restantes"
    EpisodeStatus.PLAYED -> "Écouté"
}

/** Plain-text Home screen for `podcast list`. */
fun renderHomeText(state: HomeState): String = buildString {
    if (state.resume.isNotEmpty()) {
        appendLine("Reprendre l'écoute")
        state.resume.forEach { item ->
            appendLine("  ${item.episode.title} — ${item.podcast.title}")
            appendLine("  ${progressBar(item.episode.progress)} ${item.episode.remainingMinutes} min restantes")
        }
        appendLine()
    }
    appendLine("Nouveaux épisodes")
    state.latest.forEach { item ->
        appendLine("  ${item.episode.publishedLabel.padEnd(8)} ${item.episode.title}")
        appendLine("  ${" ".repeat(8)} ${item.podcast.title} · ${item.episode.durationLabel} · ${item.statusLabel()}")
    }
}.trimEnd()
