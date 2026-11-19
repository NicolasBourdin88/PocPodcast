package fr.podcast.cli

import fr.podcast.shared.i18n.Strings
import fr.podcast.shared.model.EpisodePage
import fr.podcast.shared.model.ListeningStats
import fr.podcast.shared.model.SubscriptionItem
import kotlinx.datetime.TimeZone

/** The podcasts listed under "Most listened to". */
private const val TOP_PODCASTS = 5

/** The longest bar of `podcast stats`, in characters. */
internal const val BAR_WIDTH = 24

/** Results of `podcast episodes` (ADR 0012), numbered in the order `--play` plays them. */
fun renderEpisodeSearchText(page: EpisodePage, strings: Strings, timeZone: TimeZone = TimeZone.currentSystemDefault()): String = buildString {
    if (page.items.isEmpty()) return strings.noEpisodeFound
    appendLine(strings.episodeCount(page.total))
    page.items.forEachIndexed { index, item ->
        val episode = item.episode
        appendLine("${number(index)} ${episode.title} — ${item.podcast.title} · ${strings.shortDate(episode.publishedOn(timeZone))}")
        appendLine("    " + (listOf(strings.duration(episode.durationSeconds), strings.status(episode)) + strings.marks(episode)).joinToString(" · "))
    }
    // More match than shown (--limit).
    if (page.total > page.items.size) appendLine("  …")
}.trimEnd()

/** `podcast tags <n>`: a subscription's tags. */
fun renderTagsText(item: SubscriptionItem, strings: Strings): String =
    "${item.podcast.title} — ${strings.tags} · ${item.subscription.tags.joinToString(", ").ifEmpty { "∅" }}"

/** `podcast stats` (ADR 0012): the totals, the last twelve months as bars, the most listened podcasts. */
fun renderStatsText(stats: ListeningStats, strings: Strings): String = buildString {
    if (stats.totalSeconds == 0L && stats.episodesFinished == 0) return strings.noListeningYet
    appendLine(strings.myListening)
    val figures = listOf(strings.listeningTime to stats.totalSeconds, strings.last7Days to stats.last7DaysSeconds, strings.last30Days to stats.last30DaysSeconds)
    val figureWidth = figures.maxOf { it.first.length }
    figures.forEach { (label, seconds) -> appendLine("  ${label.padEnd(figureWidth)}  ${strings.duration(seconds.toInt())}") }
    appendLine("  " + listOfNotNull(strings.episodesFinished(stats.episodesFinished), stats.since?.let(strings::listeningSince)).joinToString(" · "))

    if (stats.byMonth.isNotEmpty()) {
        appendLine()
        appendLine(strings.byMonth)
        val longest = stats.byMonth.maxOf { it.seconds }.coerceAtLeast(1)
        val labels = stats.byMonth.map { strings.shortMonth(it.month.substringAfter('-').toIntOrNull() ?: 1) }
        val labelWidth = labels.maxOf { it.length }
        stats.byMonth.zip(labels).forEach { (month, label) ->
            val time = if (month.seconds > 0) strings.duration(month.seconds.toInt()) else ""
            appendLine("  ${label.padEnd(labelWidth)}  ${bar(month.seconds, longest).padEnd(BAR_WIDTH)}  $time".trimEnd())
        }
    }

    if (stats.byPodcast.isNotEmpty()) {
        appendLine()
        appendLine(strings.mostListened)
        val top = stats.byPodcast.take(TOP_PODCASTS)
        val titleWidth = top.maxOf { it.podcastTitle.length }
        top.forEach { appendLine("  ${it.podcastTitle.padEnd(titleWidth)}  ${strings.duration(it.seconds.toInt())}") }
    }
}.trimEnd()

/** A bar as long as [value]'s share of [longest], to the eighth of a character; never empty when [value] is not 0. */
internal fun bar(value: Long, longest: Long, width: Int = BAR_WIDTH): String {
    if (value <= 0) return ""
    val eighths = (value * width * 8 / longest).toInt().coerceIn(1, width * 8)
    return "█".repeat(eighths / 8) + (if (eighths % 8 > 0) " ▏▎▍▌▋▊▉"[eighths % 8].toString() else "")
}
