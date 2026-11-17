package fr.podcast.cli

import fr.podcast.shared.home.EpisodeItem
import fr.podcast.shared.i18n.Strings
import fr.podcast.shared.model.OpmlImportReport
import fr.podcast.shared.model.RefreshStatus
import fr.podcast.shared.model.SearchResult
import fr.podcast.shared.model.SubscriptionItem

/** Numbered results for `podcast search`; `podcast subscribe <n>` uses the numbers. */
fun renderSearchText(results: List<SearchResult>, strings: Strings): String = buildString {
    results.forEachIndexed { index, result ->
        val mark = if (result.subscribed) "  ✓ ${strings.subscribed}" else ""
        appendLine("${number(index)} ${result.title} — ${result.author}$mark")
        appendLine("    ${result.feedUrl} (${result.source})")
    }
}.trimEnd()

/** Numbered subscriptions for `podcast subscriptions`; `note` and `unsubscribe` use the numbers. */
fun renderSubscriptionsText(items: List<SubscriptionItem>, strings: Strings): String = buildString {
    if (items.isEmpty()) return strings.emptyLibrary
    items.forEachIndexed { index, item ->
        val podcast = item.podcast
        val status = when (podcast.refreshStatus) {
            RefreshStatus.PENDING -> "  [${strings.refreshPending}]"
            RefreshStatus.FAILED -> "  [${strings.refreshFailed}]"
            RefreshStatus.OK -> ""
        }
        appendLine("${number(index)} ${podcast.title} — ${podcast.author}$status")
        item.subscription.note?.takeIf { it.isNotBlank() }?.let { appendLine("    ✎ $it") }
        if (item.subscription.autoDownload) appendLine("    ↓ ${strings.autoDownload}")
    }
}.trimEnd()

fun renderOpmlReport(report: OpmlImportReport, strings: Strings): String = buildString {
    appendLine(strings.opmlReport(report.imported, report.alreadySubscribed, report.failed.size))
    report.failed.forEach { appendLine("  ✗ ${it.feedUrl} — ${it.reason}") }
}.trimEnd()

/** Numbered queue for `podcast queue`; `queue remove <n>` uses the numbers. */
fun renderQueueText(queue: List<EpisodeItem>, strings: Strings): String = buildString {
    if (queue.isEmpty()) return strings.emptyQueue
    queue.forEachIndexed { index, item ->
        appendLine("${number(index)} ${item.episode.title} — ${item.podcast.title} · ${strings.duration(item.episode.durationSeconds)}")
    }
}.trimEnd()

/** " 1.", " 2."…: the numbers commands refer to. */
internal fun number(index: Int) = "${index + 1}.".padStart(3)
