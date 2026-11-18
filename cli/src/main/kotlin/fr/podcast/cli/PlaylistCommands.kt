package fr.podcast.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.nullableFlag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import fr.podcast.shared.model.PlaylistOrder
import fr.podcast.shared.model.PlaylistRules
import fr.podcast.shared.model.SharePermission
import fr.podcast.shared.repository.PlaylistDetails

/** `podcast playlists` (ADR 0010): the user's playlists, then those shared with them; the subcommands use the numbers. */
class PlaylistsCommand : ServerCommand("playlists") {
    override val invokeWithoutSubcommand = true

    override fun help(context: Context) = systemLanguage.strings.cliPlaylistsDescription
    override fun run() {
        if (currentContext.invokedSubcommand == null) echo(renderPlaylistsText(playlists(), cli.strings))
    }
}

class PlaylistShowCommand : ServerCommand("show") {
    private val number by argument(help = "1, 2… (podcast playlists)").int()

    override fun help(context: Context) = systemLanguage.strings.cliPlaylistShowDescription
    override fun run() = echo(renderPlaylistText(number, details(number), cli.strings))
}

/** The rules of a dynamic playlist, for `create` and `edit`: a rule not given keeps its value. */
class RuleOptions : OptionGroup() {
    private val strings = systemLanguage.strings
    val keywords by option("--keywords", help = strings.keywordsHint)
    val favoritesOnly by option("--favorites", help = strings.favoritesOnly).nullableFlag("--no-favorites")
    val unplayedOnly by option("--unplayed", help = strings.unplayedOnly).nullableFlag("--no-unplayed")
    val minRating by option("--min-rating", help = "${strings.minRating} (0–5)").int().restrictTo(0..5)
    val order by option("--order", help = strings.sortOrder).enum<PlaylistOrder> { it.name.lowercase() }
    val limit by option("--limit", help = strings.episodeLimit).int().restrictTo(1..PlaylistRules.MAX_LIMIT)

    val given: Boolean get() = listOf(keywords, favoritesOnly, unplayedOnly, minRating, order, limit).any { it != null }

    fun applyTo(rules: PlaylistRules) = rules.copy(
        keywords = keywords?.let(PlaylistRules::parseKeywords) ?: rules.keywords,
        favoritesOnly = favoritesOnly ?: rules.favoritesOnly,
        unplayedOnly = unplayedOnly ?: rules.unplayedOnly,
        minRating = minRating ?: rules.minRating,
        order = order ?: rules.order,
        limit = limit ?: rules.limit,
    )
}

class PlaylistCreateCommand : ServerCommand("create") {
    private val title by argument(help = "\"Pour le train\"").multiple(required = true)
    private val description by option("--description", help = systemLanguage.strings.description)
    private val dynamic by option("--dynamic", help = systemLanguage.strings.cliDynamicOption).flag()
    private val rules by RuleOptions()

    override fun help(context: Context) = systemLanguage.strings.cliPlaylistCreateDescription
    override fun run() {
        val newRules = if (dynamic || rules.given) rules.applyTo(PlaylistRules()) else null
        val created = call { client().createPlaylist(title.joinToString(" "), description?.trim()?.ifEmpty { null }, newRules) }
        showPlaylist(created)
    }
}

class PlaylistEditCommand : ServerCommand("edit") {
    private val number by argument(help = "1, 2… (podcast playlists)").int()
    private val title by option("--title", help = systemLanguage.strings.playlistTitle)
    private val description by option("--description", help = systemLanguage.strings.description)
    private val rules by RuleOptions()

    override fun help(context: Context) = systemLanguage.strings.cliPlaylistEditDescription
    override fun run() {
        val playlist = playlist(number).playlist
        if (rules.given && playlist.rules == null) fail(cli.strings.rulesOnlyForDynamic)
        val given = description
        val newDescription = if (given == null) playlist.description else given.trim().ifEmpty { null }
        val updated = call { client().updatePlaylist(playlist.id, title ?: playlist.title, newDescription, playlist.rules?.let(rules::applyTo)) }
        echo(renderPlaylistText(number, updated, cli.strings))
    }
}

class PlaylistAddCommand : ServerCommand("add") {
    private val number by argument(help = "1, 2… (podcast playlists)").int()
    private val episode by argument(help = "1, 2… (podcast list)").int()

    override fun help(context: Context) = systemLanguage.strings.cliPlaylistAddDescription
    override fun run() {
        val playlist = playlist(number).playlist
        val item = loadHome().numbered.getOrNull(episode - 1) ?: fail(cli.strings.unknownNumber(episode))
        call { client().addToPlaylist(playlist.id, item.episode.id) }
        echo(cli.strings.addedTo(playlist.title))
    }
}

class PlaylistRemoveCommand : ServerCommand("remove") {
    private val number by argument(help = "1, 2… (podcast playlists)").int()
    private val episode by argument(help = "1, 2… (podcast playlists show)").int()

    override fun help(context: Context) = systemLanguage.strings.cliPlaylistRemoveDescription
    override fun run() {
        val details = details(number)
        val item = details.episodes.getOrNull(episode - 1) ?: fail(cli.strings.unknownNumber(episode))
        call { client().removeFromPlaylist(details.playlist.id, item.episode.id) }
        echo(renderPlaylistText(number, details(number), cli.strings))
    }
}

class PlaylistMoveCommand : ServerCommand("move") {
    private val number by argument(help = "1, 2… (podcast playlists)").int()
    private val episode by argument(help = "1, 2… (podcast playlists show)").int()
    private val position by argument(help = "1, 2…").int().restrictTo(min = 1)

    override fun help(context: Context) = systemLanguage.strings.cliPlaylistMoveDescription
    override fun run() {
        val details = details(number)
        val item = details.episodes.getOrNull(episode - 1) ?: fail(cli.strings.unknownNumber(episode))
        call { client().moveInPlaylist(details.playlist.id, item.episode.id, position - 1) }
        echo(renderPlaylistText(number, details(number), cli.strings))
    }
}

class PlaylistDeleteCommand : ServerCommand("delete") {
    private val number by argument(help = "1, 2… (podcast playlists)").int()
    private val yes by option("--yes").flag()

    override fun help(context: Context) = systemLanguage.strings.cliPlaylistDeleteDescription
    override fun run() {
        val playlist = playlist(number).playlist
        if (!yes) {
            echo(cli.strings.deletePlaylistConfirm(playlist.title))
            fail(cli.strings.confirmWithYes)
        }
        call { client().deletePlaylist(playlist.id) }
        echo(cli.strings.playlistDeleted(playlist.title))
    }
}

class PlaylistPlayCommand : ServerCommand("play") {
    private val number by argument(help = "1, 2… (podcast playlists)").int()

    override fun help(context: Context) = systemLanguage.strings.cliPlaylistPlayDescription
    override fun run() {
        val episodes = details(number).episodes.map { it.episode.id }
        if (episodes.isEmpty()) fail(cli.strings.nothingToPlay)
        runTui(loadHome(), autoplay = episodes)
    }
}

class PlaylistShuffleCommand : ServerCommand("shuffle") {
    private val number by argument(help = "1, 2… (podcast playlists)").int()

    override fun help(context: Context) = systemLanguage.strings.cliPlaylistShuffleDescription
    override fun run() {
        val playlist = playlist(number).playlist
        if (playlist.rules?.order != PlaylistOrder.RANDOM) fail(cli.strings.onlyRandomShuffles)
        echo(renderPlaylistText(number, call { client().shufflePlaylist(playlist.id) }, cli.strings))
    }
}

/** `playlists share <n>` tells whom playlist n is shared with; with a recipient, shares it (ADR 0010). */
class PlaylistShareCommand : ServerCommand("share") {
    private val number by argument(help = "1, 2… (podcast playlists)").int()
    private val recipient by argument(help = "bob").optional()
    private val write by option("--write", help = systemLanguage.strings.cliPlaylistWriteOption).flag()
    private val message by option("--message", help = systemLanguage.strings.shareMessage)

    override fun help(context: Context) = systemLanguage.strings.cliPlaylistShareDescription
    override fun run() {
        val playlist = playlist(number).playlist
        val name = recipient
            ?: return echo(renderSharesText(call { client().sharesOfPlaylist(playlist.id) }, cli.strings, cli.strings.playlistNotShared))
        val user = account(name)
        val permission = if (write) SharePermission.WRITE else SharePermission.READ
        call { client().sharePlaylist(playlist.id, user.id, permission, message) }
        echo(cli.strings.sharedWithUser(user.displayName))
    }
}

class PlaylistUnshareCommand : ServerCommand("unshare") {
    private val number by argument(help = "1, 2… (podcast playlists)").int()
    private val recipient by argument(help = "bob")

    override fun help(context: Context) = systemLanguage.strings.cliPlaylistUnshareDescription
    override fun run() {
        val playlist = playlist(number).playlist
        val user = account(recipient)
        val share = call { client().sharesOfPlaylist(playlist.id) }.firstOrNull { it.grantee.id == user.id }
            ?: fail(cli.strings.playlistNotShared)
        call { client().unshare(share.id) }
        echo(cli.strings.playlistNoLongerSharedWith(user.displayName))
    }
}

/** The secret address of a playlist's RSS feed, for any podcast app; published on first use (ADR 0010). */
class PlaylistFeedCommand : ServerCommand("feed") {
    private val number by argument(help = "1, 2… (podcast playlists)").int()
    private val renew by option("--renew", help = systemLanguage.strings.newFeedAddress).flag()
    private val off by option("--off", help = systemLanguage.strings.unpublishFeed).flag()

    override fun help(context: Context) = systemLanguage.strings.cliPlaylistFeedDescription
    override fun run() {
        val playlist = details(number).playlist
        val published = playlist.feedUrl
        when {
            off -> {
                call { client().unpublishFeed(playlist.id) }
                echo(cli.strings.feedUnpublished)
            }
            published != null && !renew -> echo(published)
            else -> {
                val url = call { client().publishFeed(playlist.id) }
                echo(cli.strings.feedPublished(playlist.title))
                echo(url)
            }
        }
    }
}

class FavoriteCommand : ServerCommand("favorite") {
    private val number by argument(help = "1, 2… (podcast list)").int()
    private val off by option("--off", help = systemLanguage.strings.removeFavorite).flag()

    override fun help(context: Context) = systemLanguage.strings.cliFavoriteDescription
    override fun run() {
        val episode = loadHome().numbered.getOrNull(number - 1)?.episode ?: fail(cli.strings.unknownNumber(number))
        call { client().setFavorite(episode.id, !off) }
        echo(if (off) cli.strings.favoriteRemoved(episode.title) else cli.strings.favoriteAdded(episode.title))
    }
}

class RateCommand : ServerCommand("rate") {
    private val number by argument(help = "1, 2… (podcast list)").int()
    private val stars by argument(help = "0–5").int().restrictTo(0..5)

    override fun help(context: Context) = systemLanguage.strings.cliRateDescription
    override fun run() {
        val episode = loadHome().numbered.getOrNull(number - 1)?.episode ?: fail(cli.strings.unknownNumber(number))
        call { client().rate(episode.id, stars.takeIf { it > 0 }) }
        echo(if (stars == 0) cli.strings.ratingCleared(episode.title) else "${episode.title} — ${cli.strings.stars(stars)}")
    }
}

/** The playlists as `podcast playlists` numbers them. */
private fun ServerCommand.playlists(): List<PlaylistEntry> {
    val client = client()
    return call { numberedPlaylists(client.playlists(), client.sharedWithMe()) }
}

private fun ServerCommand.playlist(number: Int): PlaylistEntry = playlists().getOrNull(number - 1) ?: fail(cli.strings.unknownNumber(number))

private fun ServerCommand.details(number: Int): PlaylistDetails {
    val id = playlist(number).playlist.id
    return call { client().playlist(id) } ?: fail(cli.strings.unknownNumber(number))
}

/** A playlist just created, under the number `podcast playlists` gives it. */
private fun ServerCommand.showPlaylist(details: PlaylistDetails) {
    val number = playlists().indexOfFirst { it.playlist.id == details.playlist.id } + 1
    echo(renderPlaylistText(number, details, cli.strings))
}
