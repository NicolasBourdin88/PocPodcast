package fr.podcast.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import fr.podcast.shared.model.SharePermission
import fr.podcast.shared.model.User

/** `podcast shared`: what others share with the user (ADR 0009); `shared remove <n>` declines. */
class SharedCommand : ServerCommand("shared") {
    override val invokeWithoutSubcommand = true

    override fun help(context: Context) = systemLanguage.strings.cliSharedDescription
    override fun run() {
        if (currentContext.invokedSubcommand == null) echo(renderSharedText(call { client().sharedWithMe() }, cli.strings))
    }
}

class SharedRemoveCommand : ServerCommand("remove") {
    private val number by argument(help = "1, 2… (podcast shared)").int()

    override fun help(context: Context) = systemLanguage.strings.cliSharedRemoveDescription
    override fun run() {
        val item = call { client().sharedWithMe() }.getOrNull(number - 1) ?: fail(cli.strings.unknownNumber(number))
        call { client().unshare(item.share.id) }
        echo(cli.strings.shareRemoved)
    }
}

/**
 * `podcast share <n>` tells whom subscription n is shared with; `podcast share <n> <user>` shares
 * it, read-only or with --write. Sharing again replaces the permission and the message.
 */
class ShareCommand : ServerCommand("share") {
    private val number by argument(help = "1, 2… (podcast subscriptions)").int()
    private val recipient by argument(help = "bob").optional()
    private val write by option("--write", help = systemLanguage.strings.cliWriteOption).flag()
    private val message by option("--message", help = systemLanguage.strings.shareMessage)

    override fun help(context: Context) = systemLanguage.strings.cliShareDescription
    override fun run() {
        val item = subscription(number)
        val name = recipient ?: return echo(renderSharesText(call { client().sharesOf(item.subscription.id) }, cli.strings))
        val user = account(name)
        val permission = if (write) SharePermission.WRITE else SharePermission.READ
        call { client().shareSubscription(item.subscription.id, user.id, permission, message) }
        echo(cli.strings.sharedWithUser(user.displayName))
    }
}

class UnshareCommand : ServerCommand("unshare") {
    private val number by argument(help = "1, 2… (podcast subscriptions)").int()
    private val recipient by argument(help = "bob")

    override fun help(context: Context) = systemLanguage.strings.cliUnshareDescription
    override fun run() {
        val item = subscription(number)
        val user = account(recipient)
        val share = call { client().sharesOf(item.subscription.id) }.firstOrNull { it.grantee.id == user.id } ?: fail(cli.strings.notShared)
        call { client().unshare(share.id) }
        echo(cli.strings.noLongerSharedWith(user.displayName))
    }
}

class RecommendCommand : ServerCommand("recommend") {
    private val number by argument(help = "1, 2… (podcast list)").int()
    private val recipient by argument(help = "bob")
    private val message by option("--message", help = systemLanguage.strings.shareMessage)

    override fun help(context: Context) = systemLanguage.strings.cliRecommendDescription
    override fun run() {
        val item = loadHome().numbered.getOrNull(number - 1) ?: fail(cli.strings.unknownNumber(number))
        val user = account(recipient)
        call { client().recommend(item.episode.id, user.id, message) }
        echo(cli.strings.recommendedTo(user.displayName))
    }
}

/** An account of the server, by its username or its display name, in any case. */
private fun ServerCommand.account(name: String): User {
    val users = call { client().users() }
    return users.firstOrNull { it.username.equals(name, ignoreCase = true) }
        ?: users.firstOrNull { it.displayName.equals(name, ignoreCase = true) }
        ?: fail(cli.strings.noSuchUser)
}
