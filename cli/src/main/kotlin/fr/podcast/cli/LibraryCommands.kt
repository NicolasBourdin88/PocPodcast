package fr.podcast.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import fr.podcast.shared.model.SubscriptionItem

class SearchCommand : ServerCommand("search") {
    private val terms by argument(help = "kotlin, \"les cast codeurs\"…").multiple(required = true)

    override fun help(context: Context) = systemLanguage.strings.cliSearchDescription
    override fun run() {
        val query = terms.joinToString(" ")
        val results = call { client().search(query) } ?: fail(cli.strings.searchUnavailable)
        cli.lastSearch = results.map { it.feedUrl }
        echo(if (results.isEmpty()) cli.strings.noResults(query) else renderSearchText(results, cli.strings))
    }
}

class SubscribeCommand : ServerCommand("subscribe") {
    private val feed by argument(help = "https://…/feed.xml | 1, 2…")

    override fun help(context: Context) = systemLanguage.strings.cliSubscribeDescription
    override fun run() {
        val feedUrl = feed.toIntOrNull()?.let { cli.lastSearch.getOrNull(it - 1) ?: fail(cli.strings.unknownNumber(it)) } ?: feed
        val item = call { client().subscribe(feedUrl) }
        echo(cli.strings.subscribedTo(item.podcast.title))
    }
}

class SubscriptionsCommand : ServerCommand("subscriptions") {
    override fun help(context: Context) = systemLanguage.strings.cliSubscriptionsDescription
    override fun run() = echo(renderSubscriptionsText(call { client().subscriptionItems() }, cli.strings))
}

class NoteCommand : ServerCommand("note") {
    private val number by argument(help = "1, 2…").int()
    private val text by argument().multiple()

    override fun help(context: Context) = systemLanguage.strings.cliNoteDescription
    override fun run() {
        val item = subscription(number)
        val note = text.joinToString(" ").trim().ifEmpty { null }
        val updated = call { client().updateSubscription(item.subscription.id, note, item.subscription.autoDownload) }
        echo("${updated.podcast.title} — ✎ ${updated.subscription.note ?: "∅"}")
    }
}

class UnsubscribeCommand : ServerCommand("unsubscribe") {
    private val number by argument(help = "1, 2…").int()

    override fun help(context: Context) = systemLanguage.strings.cliUnsubscribeDescription
    override fun run() {
        val item = subscription(number)
        call { client().unsubscribe(item.subscription.id) }
        echo(cli.strings.unsubscribedFrom(item.podcast.title))
    }
}

/** `podcast opml export [file]` and `podcast opml import <file>`. */
class OpmlCommand : CliktCommand(name = "opml") {
    override fun help(context: Context) = "${systemLanguage.strings.opmlExport} · ${systemLanguage.strings.opmlImport}"
    override fun run() = Unit
}

class OpmlExportCommand : ServerCommand("export") {
    private val file by argument(help = "podcasts.opml").file(canBeDir = false).optional()

    override fun help(context: Context) = systemLanguage.strings.cliOpmlExportDescription
    override fun run() {
        val opml = call { client().exportOpml() }
        val target = file
        if (target == null) {
            echo(opml)
        } else {
            target.writeText(opml)
            echo(cli.strings.opmlSaved(target.path))
        }
    }
}

class OpmlImportCommand : ServerCommand("import") {
    private val file by argument(help = "podcasts.opml").file(mustExist = true, canBeDir = false, mustBeReadable = true)

    override fun help(context: Context) = systemLanguage.strings.cliOpmlImportDescription
    override fun run() = echo(renderOpmlReport(call { client().importOpml(file.readText()) }, cli.strings))
}

/** The subscription shown under [number] by `podcast subscriptions`. */
private fun ServerCommand.subscription(number: Int): SubscriptionItem =
    call { client().subscriptionItems() }.getOrNull(number - 1) ?: fail(cli.strings.unknownNumber(number))
