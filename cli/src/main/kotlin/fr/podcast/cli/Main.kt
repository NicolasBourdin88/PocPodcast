package fr.podcast.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.findOrSetObject
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.jakewharton.mosaic.runMosaicBlocking
import fr.podcast.shared.home.HomeState
import fr.podcast.shared.home.LoadHomeState
import fr.podcast.shared.i18n.Language
import fr.podcast.shared.repository.FakePodcastRepository
import kotlinx.coroutines.runBlocking
import java.util.Locale

/** Language of the system locale; also used for --help, which is shown before options are parsed. */
private val systemLanguage = Language.fromTag(Locale.getDefault().toLanguageTag()) ?: Language.Default

private fun loadHome(): HomeState = runBlocking { LoadHomeState(FakePodcastRepository())() }

class PodcastCommand : CliktCommand(name = "podcast") {
    private val language by option("--lang", help = systemLanguage.strings.cliLanguageOption)
        .convert { tag -> Language.fromTag(tag) ?: fail(Language.entries.joinToString(", ") { it.tag }) }
        .default(systemLanguage, defaultForHelp = systemLanguage.tag)

    override fun help(context: Context) = systemLanguage.strings.cliDescription

    override fun run() {
        currentContext.findOrSetObject { language }
    }
}

class ListCommand : CliktCommand(name = "list") {
    private val language by requireObject<Language>()

    override fun help(context: Context) = systemLanguage.strings.cliListDescription
    override fun run() = echo(renderHomeText(loadHome(), language.strings))
}

class TuiCommand : CliktCommand(name = "tui") {
    private val language by requireObject<Language>()

    override fun help(context: Context) = systemLanguage.strings.cliTuiDescription
    override fun run() {
        val home = loadHome()
        runMosaicBlocking { HomeTui(home, language.strings) }
    }
}

fun main(args: Array<String>) = PodcastCommand()
    .subcommands(ListCommand(), TuiCommand())
    .main(args)
