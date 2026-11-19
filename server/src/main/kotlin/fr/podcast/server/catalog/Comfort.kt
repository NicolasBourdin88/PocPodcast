package fr.podcast.server.catalog

import fr.podcast.server.db.PodcastDb
import fr.podcast.server.db.ServerDatabase
import fr.podcast.server.db.StateBefore
import fr.podcast.shared.model.ListeningStats
import fr.podcast.shared.model.MonthListening
import fr.podcast.shared.model.PodcastListening
import fr.podcast.shared.model.SubscriptionItem
import fr.podcast.shared.model.TagRules
import kotlinx.datetime.toKotlinLocalDate
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneOffset

// Tags, search and listening statistics (ADR 0012).

/** The tags of these subscriptions, by subscription id, in the order of their names. */
internal fun PodcastDb.tagsOf(subscriptionIds: Collection<Long>): Map<Long, List<String>> =
    if (subscriptionIds.isEmpty()) {
        emptyMap()
    } else {
        comfortQueries.tagsOfSubscriptions(subscriptionIds).executeAsList().groupBy({ it.subscription_id }, { it.tag }).mapValues { TagRules.sorted(it.value) }
    }

internal fun PodcastDb.withTags(items: List<SubscriptionItem>): List<SubscriptionItem> {
    val tags = tagsOf(items.map { it.subscription.id.toLong() })
    return items.map { item -> tags[item.subscription.id.toLong()]?.let { item.copy(subscription = item.subscription.copy(tags = it)) } ?: item }
}

/** Replaces the subscription's tags, already checked (TagRules). */
internal fun PodcastDb.replaceTags(subscriptionId: Long, tags: List<String>) {
    comfortQueries.deleteTags(subscriptionId)
    tags.forEach { comfortQueries.insertTag(subscriptionId, it) }
}

/**
 * "kotlin  Ktor" → `^(?=.*kotlin)(?=.*Ktor)`: every word somewhere, in any order (searched without
 * case). Empty when there is no word.
 */
internal fun searchPattern(text: String): String {
    val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return ""
    return "^" + words.joinToString("") { "(?=.*${it.replace(REGEX_SPECIAL) { special -> "\\" + special.value }})" }
}

private val REGEX_SPECIAL = Regex("""[\\.^$|?*+()\[\]{}]""")

/** Today in UTC: the days of the statistics. */
internal fun today(): LocalDate = LocalDate.now(ZoneOffset.UTC)

/**
 * What a new position adds to the listening time: the gap with the previous one when it is
 * plausible, at most 3 times the time elapsed since (the fastest speed) plus 30 s. A jump ahead
 * is not listening.
 */
internal fun listenedBetween(before: StateBefore?, position: Int, now: OffsetDateTime): Long {
    val gap = position - (before?.position_seconds ?: 0)
    if (gap <= 0) return 0
    val elapsed = before?.updated_at?.let { Duration.between(it, now).seconds.coerceAtLeast(0) } ?: 0
    return if (gap <= 3 * elapsed + 30) gap.toLong() else 0
}

/** Adds to the user's listening of the episode's podcast that day. */
internal fun PodcastDb.recordListening(userId: Long, episodeId: Long, seconds: Long, finished: Int, day: LocalDate = today()) {
    if (seconds <= 0 && finished <= 0) return
    val podcast = comfortQueries.episodePodcast(episodeId).executeAsOneOrNull() ?: return
    comfortQueries.recordListening(userId, day, podcast.feed_url, podcast.title, seconds.coerceAtLeast(0), finished)
}

/** The statistics of ADR 0012: totals, the most listened podcasts, the last twelve months. */
class ListeningStatistics(private val database: ServerDatabase) {
    suspend fun of(userId: Long, today: LocalDate = today()): ListeningStats = database.query {
        val days = comfortQueries.listeningPerDay(userId).executeAsList()
        val thisMonth = YearMonth.from(today)
        ListeningStats(
            totalSeconds = days.sumOf { it.seconds },
            last7DaysSeconds = days.filter { it.day > today.minusDays(7) }.sumOf { it.seconds },
            last30DaysSeconds = days.filter { it.day > today.minusDays(30) }.sumOf { it.seconds },
            episodesFinished = days.sumOf { it.finished },
            since = days.firstOrNull()?.day?.toKotlinLocalDate(),
            byPodcast = comfortQueries.listeningPerPodcast(userId, TOP_PODCASTS).executeAsList().map {
                PodcastListening(it.podcast_title ?: it.feed_url, it.feed_url, it.seconds ?: 0, it.finished ?: 0)
            },
            byMonth = (11L downTo 0L).map { back ->
                val month = thisMonth.minusMonths(back)
                MonthListening(month.toString(), days.filter { YearMonth.from(it.day) == month }.sumOf { it.seconds })
            },
        )
    }

    private companion object {
        const val TOP_PODCASTS = 10L
    }
}
