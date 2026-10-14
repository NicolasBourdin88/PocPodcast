import com.sun.net.httpserver.HttpServer
import javafx.application.Platform
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.util.Duration
import java.awt.EventQueue
import java.awt.Toolkit
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Throwaway POC for study 3 (audio playback): can JavaFX Media be the desktop backend?
 * Real podcast files, muted. Writes a Markdown report to the path given as first argument.
 */
fun main(args: Array<String>) {
    // JavaFX downloads through java.net, whose default User-Agent ("Java/21…") some CDNs block.
    if ("--default-agent" !in args) System.setProperty("http.agent", "Podcast/0.5 (master project)")
    val report = StringBuilder("# POC lecture audio — JavaFX Media (desktop)\n\n")
    report.appendLine("JDK ${System.getProperty("java.version")}, ${System.getProperty("os.name")} ${System.getProperty("os.arch")}, User-Agent : ${System.getProperty("http.agent") ?: "par défaut (Java/…)"}.\n")

    // Like a Compose Desktop app: AWT is up before JavaFX starts.
    EventQueue.invokeAndWait { Toolkit.getDefaultToolkit() }
    val started = CountDownLatch(1)
    val startMillis = System.currentTimeMillis()
    Platform.setImplicitExit(false)
    Platform.startup { started.countDown() }
    val toolkitOk = started.await(15, TimeUnit.SECONDS)
    report.appendLine("- Toolkit JavaFX démarré après AWT : ${if (toolkitOk) "oui (${System.currentTimeMillis() - startMillis} ms)" else "NON (bloqué)"}")
    if (!toolkitOk) finish(report, args)

    // An http address that redirects to https, like the dev server's /episodes/{id}/audio.
    val mp3 = "https://op3.dev/e/www.buzzsprout.com/1538779/episodes/19933418-podcast-ad-revenue-is-increasing.mp3"
    val m4a = "https://anchor.fm/s/fdcf2fa8/podcast/play/110110638/https%3A%2F%2Fd3ctxlq1ktw2nl.cloudfront.net%2Fstaging%2F2025-9-23%2F409835143-44100-2-7ca459809c055.m4a"
    val redirector = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/audio") { exchange ->
            exchange.responseHeaders.add("Location", mp3)
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        start()
    }
    val local = "http://127.0.0.1:${redirector.address.port}/audio"

    val cases = listOf(
        "MP3, redirections https (op3 → buzzsprout → CDN)" to mp3,
        "AAC (.m4a), redirection anchor.fm → CloudFront" to m4a,
        "MP3 derrière une redirection http → https" to local,
    )
    report.appendLine()
    report.appendLine("| Cas | Adresse donnée à JavaFX | Résultat | Prêt en | Durée | Reprise à 600 s (après 4 s) | Vitesse mesurée à 1,5× |")
    report.appendLine("|---|---|---|---|---|---|---|")
    for ((name, url) in cases) {
        for ((variant, address) in listOf("telle quelle" to url, "après résolution des redirections" to resolve(url))) {
            report.appendLine("| $name | $variant | ${probe(address)} |")
            println(report.lines().last { it.isNotBlank() })
        }
    }
    redirector.stop(0)

    report.appendLine()
    report.appendLine("Poids des jars JavaFX (natifs compris) :")
    System.getProperty("java.class.path").split(File.pathSeparator)
        .map(::File).filter { it.name.startsWith("javafx-") }
        .forEach { report.appendLine("- ${it.name} : ${it.length() / 1024} Kio") }
    finish(report, args)
}

/** Final address after every redirect, the way the desktop player would resolve it. */
private fun resolve(url: String): String {
    val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()
    val request = HttpRequest.newBuilder(URI(url)).header("Range", "bytes=0-0").GET().build()
    return client.send(request, HttpResponse.BodyHandlers.discarding()).uri().toString()
}

/** Loads, resumes at 600 s, then plays at 1.5×: "result | ready in | duration | resume | speed". */
private fun probe(url: String): String {
    val player = try {
        MediaPlayer(Media(url))
    } catch (failure: Exception) {
        return "échec : ${failure.javaClass.simpleName} ${failure.message?.take(60)} | | | | "
    }
    try {
        player.volume = 0.0
        val ready = CountDownLatch(1)
        var error: String? = null
        player.setOnReady { ready.countDown() }
        player.setOnError {
            error = player.error?.let { "${it.type} ${it.message?.take(60)}" }
            ready.countDown()
        }
        val loadStart = System.currentTimeMillis()
        if (!ready.await(30, TimeUnit.SECONDS)) return "délai dépassé | | | | "
        error?.let { return "échec : $it | | | | " }
        val readyIn = System.currentTimeMillis() - loadStart
        val duration = player.totalDuration.toSeconds()

        player.seek(Duration.seconds(600.0))
        player.play()
        Thread.sleep(4_000)
        val resumed = player.currentTime.toSeconds()

        val before = player.currentTime.toSeconds()
        player.rate = 1.5
        Thread.sleep(6_000)
        val speed = (player.currentTime.toSeconds() - before) / 6.0

        return "OK | $readyIn ms | ${duration.toInt()} s | ${"%.1f".format(resumed)} s | ${"%.2f".format(speed)}×"
    } finally {
        player.stop()
        player.dispose()
    }
}

private fun finish(report: StringBuilder, args: Array<String>): Nothing {
    val target = File(args.firstOrNull() ?: "poc-report.md")
    target.parentFile?.mkdirs()
    target.writeText(report.toString())
    println(report)
    Platform.exit()
    exitProcess(0)
}
