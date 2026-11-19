package fr.podcast.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.RawOption
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import fr.podcast.shared.model.EpisodeQuery
import fr.podcast.shared.model.EpisodeStatus
import fr.podcast.shared.model.TagRules
import kotlinx.datetime.LocalDate

/** The most results `podcast episodes` asks for. */
private const val MAX_RESULTS = 100

/**
 * `podcast episodes [words…]` (ADR 0012): the search of the user's episodes, with every criterion
 * of GET /episodes; `--play` plays what is found, in order.
 */
class EpisodesCommand : ServerCommand("episodes") {
    private val strings = systemLanguage.strings
    private val words by argument(help = "kotlin ktor").multiple()
    private val status by option("--status", help = strings.statusLabel).enum<EpisodeStatus> { it.name.lowercase().replace('_', '-') }
    private val favorites by option("--favorites", help = strings.favoritesOnly).flag()
    private val minRating by option("--min-rating", help = "${strings.minRating} (0–5)").int().restrictTo(0..5).default(0)
    private val from by option("--from", help = "${strings.period} · ${strings.publishedFrom}").date()
    private val to by option("--to", help = "${strings.period} · ${strings.publishedTo}").date()
    private val podcast by option("--podcast", help = "1, 2… (podcast subscriptions)").int()
    private val tag by option("--tag", help = strings.tags)
    private val limit by option("--limit", help = strings.episodeLimit).int().restrictTo(1..MAX_RESULTS).default(20)
    private val play by option("--play", help = strings.playAll).flag()

    override fun help(context: Context) = strings.cliEpisodesDescription
    override fun run() {
        val podcastId = podcast?.let { subscription(it).podcast.id }
        val query = EpisodeQuery(words.joinToString(" "), status, favorites, minRating, from, to, podcastId, tag)
        val page = call { client().searchEpisodes(query, limit) }
        if (!play) return echo(renderEpisodeSearchText(page, cli.strings))
        if (page.items.isEmpty()) fail(cli.strings.noEpisodeFound)
        runTui(loadHome(), autoplay = page.items.map { it.episode.id })
    }
}

/** `podcast tags <n> [tags…]` (ADR 0012): shows a subscription's tags, or replaces them. */
class TagsCommand : ServerCommand("tags") {
    private val number by argument(help = "1, 2… (podcast subscriptions)").int()
    private val tags by argument(help = "tech english \"long form\"").multiple()
    private val clear by option("--clear", help = systemLanguage.strings.clearTags).flag()

    override fun help(context: Context) = systemLanguage.strings.cliTagsDescription
    override fun run() {
        val item = subscription(number)
        if (tags.isEmpty() && !clear) return echo(renderTagsText(item, cli.strings))
        // "tech, english" as well as tech english.
        val chosen = if (clear) emptyList() else TagRules.parse(tags.joinToString(","))
        if (!TagRules.valid(chosen)) fail(cli.strings.tagsInvalid(TagRules.MAX_TAGS, TagRules.MAX_LENGTH))
        val subscription = item.subscription
        echo(renderTagsText(call { client().updateSubscription(subscription.id, subscription.note, subscription.autoDownload, chosen) }, cli.strings))
    }
}

/** `podcast stats` (ADR 0012): the user's listening. */
class StatsCommand : ServerCommand("stats") {
    override fun help(context: Context) = systemLanguage.strings.cliStatsDescription
    override fun run() = echo(renderStatsText(call { client().listeningStats() }, cli.strings))
}

/** A date written YYYY-MM-DD. */
private fun RawOption.date() = convert("YYYY-MM-DD") { value ->
    runCatching { LocalDate.parse(value) }.getOrElse { fail("YYYY-MM-DD") }
}
