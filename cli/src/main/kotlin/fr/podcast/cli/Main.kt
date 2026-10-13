package fr.podcast.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.jakewharton.mosaic.runMosaicBlocking
import fr.podcast.shared.home.HomeState
import fr.podcast.shared.home.LoadHomeState
import fr.podcast.shared.repository.FakePodcastRepository
import kotlinx.coroutines.runBlocking

private fun loadHome(): HomeState = runBlocking { LoadHomeState(FakePodcastRepository())() }

class PodcastCommand : CliktCommand(name = "podcast") {
    override fun help(context: Context) = "Client en ligne de commande du système de podcasts."
    override fun run() = Unit
}

class ListCommand : CliktCommand(name = "list") {
    override fun help(context: Context) = "Affiche l'écran Accueil en texte."
    override fun run() = echo(renderHomeText(loadHome()))
}

class TuiCommand : CliktCommand(name = "tui") {
    override fun help(context: Context) = "Ouvre l'écran Accueil en mode interactif (↑/↓ pour naviguer, q pour quitter)."
    override fun run() {
        val home = loadHome()
        runMosaicBlocking { HomeTui(home) }
    }
}

fun main(args: Array<String>) = PodcastCommand()
    .subcommands(ListCommand(), TuiCommand())
    .main(args)
