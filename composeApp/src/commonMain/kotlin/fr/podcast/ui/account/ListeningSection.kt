package fr.podcast.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.podcast.shared.model.ListeningStats
import fr.podcast.shared.model.MonthListening
import fr.podcast.ui.theme.LocalStrings

/** The podcasts listed under "Most listened to". */
private const val TOP_PODCASTS = 5

/**
 * How much the user listened (ADR 0012): totals, the last twelve months as bars, the most
 * listened podcasts.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ListeningSection(stats: ListeningStats, modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    Column(modifier.widthIn(max = 560.dp).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (stats.totalSeconds == 0L && stats.episodesFinished == 0) {
            Text(strings.noListeningYet, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Figure(strings.duration(stats.totalSeconds.toInt()), strings.listeningTime)
            Figure(strings.duration(stats.last7DaysSeconds.toInt()), strings.last7Days)
            Figure(strings.duration(stats.last30DaysSeconds.toInt()), strings.last30Days)
        }
        Text(
            listOfNotNull(strings.episodesFinished(stats.episodesFinished), stats.since?.let(strings::listeningSince)).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(strings.byMonth, style = MaterialTheme.typography.titleSmall)
        MonthBars(stats.byMonth)
        if (stats.byPodcast.isNotEmpty()) {
            Text(strings.mostListened, style = MaterialTheme.typography.titleSmall)
            stats.byPodcast.take(TOP_PODCASTS).forEach { podcast ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(podcast.podcastTitle, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(strings.duration(podcast.seconds.toInt()), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Figure(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.headlineSmall)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One bar per month, as high as its share of the longest one; read out as text. */
@Composable
private fun MonthBars(months: List<MonthListening>) {
    val strings = LocalStrings.current
    val longest = months.maxOfOrNull { it.seconds }?.takeIf { it > 0 } ?: 1L
    fun label(month: MonthListening) = strings.shortMonth(month.month.substringAfter('-').toIntOrNull() ?: 1)
    val description = months.joinToString(", ") { "${label(it)} ${strings.duration(it.seconds.toInt())}" }
    Row(
        Modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        months.forEach { month ->
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier.fillMaxWidth(0.7f)
                            .fillMaxHeight(month.seconds.toFloat() / longest)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)),
                    )
                }
                Text(label(month), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}
