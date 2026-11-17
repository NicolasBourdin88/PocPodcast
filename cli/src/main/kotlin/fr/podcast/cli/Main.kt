package fr.podcast.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.findOrSetObject
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.prompt
import com.jakewharton.mosaic.runMosaicBlocking
import fr.podcast.player.JavaFxAudioPlayer
import fr.podcast.player.PlaybackController
import fr.podcast.player.SimulatedAudioPlayer
import fr.podcast.shared.account.AccountService
import fr.podcast.shared.account.AccountStore
import fr.podcast.shared.account.PreferencesKeyValueStore
import fr.podcast.shared.api.podcastHttpClient
import fr.podcast.shared.api.userMessage
import fr.podcast.shared.home.HomeState
import fr.podcast.shared.home.LoadHomeState
import fr.podcast.shared.i18n.Language
import fr.podcast.shared.i18n.Strings
import fr.podcast.shared.repository.FakePodcastRepository
import fr.podcast.shared.repository.PodcastClient
import fr.podcast.shared.repository.RemotePodcastRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.Executors
import java.util.prefs.Preferences
import kotlin.time.Duration.Companion.seconds

/** Language of the system locale; also used for --help, which is shown before options are parsed. */
internal val systemLanguage = Language.fromTag(Locale.getDefault().toLanguageTag()) ?: Language.Default

/** Shared by every command: language, saved accounts, HTTP client. */
class CliContext(val strings: Strings, val demo: Boolean) {
    private val preferences = Preferences.userRoot().node("fr/podcast/cli")
    val httpClient by lazy { podcastHttpClient() }
    val accounts = AccountStore(PreferencesKeyValueStore(preferences))
    val accountService by lazy { AccountService(httpClient, "CLI (${System.getProperty("os.name")})") }

    /** Demo data with --demo, otherwise the active account's server; one client per command. */
    private val client: PodcastClient? by lazy {
        if (demo) FakePodcastRepository() else accounts.state.value.active?.let { RemotePodcastRepository(it, httpClient) }
    }

    fun repository(): PodcastClient? = client

    /** Feeds of the last `podcast search`, so that `podcast subscribe 2` works. */
    var lastSearch: List<String>
        get() = preferences.get(LAST_SEARCH, "").lines().filter { it.isNotBlank() }
        set(value) = preferences.put(LAST_SEARCH, value.joinToString("\n"))

    private companion object {
        const val LAST_SEARCH = "lastSearch"
    }
}

class PodcastCommand : CliktCommand(name = "podcast") {
    private val language by option("--lang", help = systemLanguage.strings.cliLanguageOption)
        .convert { tag -> Language.fromTag(tag) ?: fail(Language.entries.joinToString(", ") { it.tag }) }
        .default(systemLanguage, defaultForHelp = systemLanguage.tag)
    private val demo by option("--demo", help = systemLanguage.strings.cliDemoOption).flag()

    override fun help(context: Context) = systemLanguage.strings.cliDescription

    override fun run() {
        currentContext.findOrSetObject { CliContext(language.strings, demo) }
    }
}

/** Base for commands that run against a server: turns API failures into messages. */
abstract class ServerCommand(name: String) : CliktCommand(name = name) {
    val cli by requireObject<CliContext>()

    fun <T> call(block: suspend () -> T): T =
        try {
            runBlocking { block() }
        } catch (failure: Exception) {
            echo(failure.userMessage(cli.strings), err = true)
            throw ProgramResult(1)
        }

    /** The active account's server, or the demo data. */
    fun client(): PodcastClient = cli.repository() ?: run {
        echo(cli.strings.noAccount, err = true)
        throw ProgramResult(1)
    }

    protected fun loadHome(): HomeState {
        val client = client()
        return call { LoadHomeState(client)() }
    }

    fun fail(message: String): Nothing {
        echo(message, err = true)
        throw ProgramResult(1)
    }

    /**
     * The interactive screen, playing on JavaFX Media (ADR 0008), or silently with --demo; starts
     * [autoplay] at once. The position reaches the server before the command ends.
     */
    protected fun runTui(home: HomeState, autoplay: String?) {
        val client = client()
        // One thread: playback commands run in the order they are given.
        val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "playback").apply { isDaemon = true } }.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val player = if (cli.demo) SimulatedAudioPlayer(scope) else JavaFxAudioPlayer()
        val playback = PlaybackController(player, scope)
        playback.attach(client)
        autoplay?.let(playback::play)
        try {
            runMosaicBlocking { PodcastTui(home, playback, cli.strings) }
        } finally {
            runBlocking(dispatcher) { withTimeoutOrNull(3.seconds) { playback.flush() } }
            player.release()
            scope.cancel()
            dispatcher.close()
        }
    }
}

class ListCommand : ServerCommand("list") {
    override fun help(context: Context) = systemLanguage.strings.cliListDescription
    override fun run() = echo(renderHomeText(loadHome(), cli.strings))
}

class TuiCommand : ServerCommand("tui") {
    override fun help(context: Context) = systemLanguage.strings.cliTuiDescription
    override fun run() = runTui(loadHome(), autoplay = null)
}

class PlayCommand : ServerCommand("play") {
    private val number by argument(help = "1, 2… (podcast list)").int().optional()

    override fun help(context: Context) = systemLanguage.strings.cliPlayDescription
    override fun run() {
        val home = loadHome()
        val episodeId = number?.let { home.numbered.getOrNull(it - 1)?.episode?.id ?: fail(cli.strings.unknownNumber(it)) }
            ?: home.resume.firstOrNull()?.episode?.id
            ?: call { client().queue() }.firstOrNull()?.episode?.id
            ?: fail(cli.strings.nothingToPlay)
        runTui(home, autoplay = episodeId)
    }
}

/** `podcast queue`, and `queue add <n> [--next]`, `queue remove <n>`, `queue clear`. */
class QueueCommand : ServerCommand("queue") {
    override val invokeWithoutSubcommand = true

    override fun help(context: Context) = systemLanguage.strings.cliQueueDescription
    override fun run() {
        if (currentContext.invokedSubcommand == null) echo(renderQueueText(call { client().queue() }, cli.strings))
    }
}

class QueueAddCommand : ServerCommand("add") {
    private val number by argument(help = "1, 2… (podcast list)").int()
    private val next by option("--next", help = systemLanguage.strings.playNext).flag()

    override fun help(context: Context) = systemLanguage.strings.cliQueueAddDescription
    override fun run() {
        val item = loadHome().numbered.getOrNull(number - 1) ?: fail(cli.strings.unknownNumber(number))
        call { client().enqueue(item.episode.id, if (next) 0 else null) }
        echo(renderQueueText(call { client().queue() }, cli.strings))
    }
}

class QueueRemoveCommand : ServerCommand("remove") {
    private val number by argument(help = "1, 2… (podcast queue)").int()

    override fun help(context: Context) = systemLanguage.strings.cliQueueRemoveDescription
    override fun run() {
        val item = call { client().queue() }.getOrNull(number - 1) ?: fail(cli.strings.unknownNumber(number))
        call { client().dequeue(item.episode.id) }
        echo(renderQueueText(call { client().queue() }, cli.strings))
    }
}

class QueueClearCommand : ServerCommand("clear") {
    override fun help(context: Context) = systemLanguage.strings.cliQueueClearDescription
    override fun run() {
        call { client().clearQueue() }
        echo(cli.strings.emptyQueue)
    }
}

class LoginCommand : ServerCommand("login") {
    private val server by option("--server").prompt(systemLanguage.strings.serverUrl, default = "http://localhost:8080")
    private val username by option("--user").prompt(systemLanguage.strings.username)
    private val password by option("--password").prompt(systemLanguage.strings.password, hideInput = true)

    override fun help(context: Context) = systemLanguage.strings.cliLoginDescription
    override fun run() {
        val account = call { cli.accountService.signIn(server, username, password) }
        cli.accounts.save(account)
        echo(cli.strings.accountSaved(account.displayName, "${account.serverName} (${account.serverUrl})"))
    }
}

class AccountsCommand : ServerCommand("accounts") {
    override fun help(context: Context) = systemLanguage.strings.cliAccountsDescription
    override fun run() {
        val state = cli.accounts.state.value
        if (state.accounts.isEmpty()) echo(cli.strings.noAccount)
        state.accounts.forEachIndexed { index, account ->
            val marker = if (account.id == state.activeId) "*" else " "
            echo("$marker ${index + 1}. ${account.displayName} — ${account.username} · ${account.serverName} (${account.serverUrl})")
        }
    }
}

class UseCommand : ServerCommand("use") {
    private val account by argument(help = "1, 2… (podcast accounts)")

    override fun help(context: Context) = systemLanguage.strings.cliUseDescription
    override fun run() {
        val accounts = cli.accounts.state.value.accounts
        val chosen = account.toIntOrNull()?.let { accounts.getOrNull(it - 1) } ?: accounts.firstOrNull { it.id == account }
        if (chosen == null) {
            echo(cli.strings.unknownAccount, err = true)
            throw ProgramResult(1)
        }
        cli.accounts.select(chosen.id)
        echo("* ${chosen.displayName} — ${chosen.serverName} (${chosen.serverUrl})")
    }
}

class LogoutCommand : ServerCommand("logout") {
    override fun help(context: Context) = systemLanguage.strings.cliLogoutDescription
    override fun run() {
        val active = cli.accounts.state.value.active ?: run {
            echo(cli.strings.noAccount, err = true)
            throw ProgramResult(1)
        }
        runBlocking { cli.accountService.signOut(active) }
        cli.accounts.remove(active.id)
        echo(cli.strings.signedOut)
    }
}

fun main(args: Array<String>) = PodcastCommand()
    .subcommands(
        ListCommand(), TuiCommand(), PlayCommand(),
        QueueCommand().subcommands(QueueAddCommand(), QueueRemoveCommand(), QueueClearCommand()),
        SearchCommand(), SubscribeCommand(), SubscriptionsCommand(), NoteCommand(), UnsubscribeCommand(),
        OpmlCommand().subcommands(OpmlExportCommand(), OpmlImportCommand()),
        SharedCommand().subcommands(SharedRemoveCommand()), ShareCommand(), UnshareCommand(), RecommendCommand(),
        LoginCommand(), AccountsCommand(), UseCommand(), LogoutCommand(),
        ProfileCommand(), PasswdCommand(), DevicesCommand().subcommands(DevicesRevokeCommand()),
        UsersCommand().subcommands(UsersAddCommand(), UsersRemoveCommand(), UsersPasswdCommand(), UsersRoleCommand()),
    )
    .main(args)
