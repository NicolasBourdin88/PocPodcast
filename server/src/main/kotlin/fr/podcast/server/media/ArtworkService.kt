package fr.podcast.server.media

import fr.podcast.server.feeds.FeedException
import fr.podcast.server.feeds.LimitedInputStream
import fr.podcast.server.feeds.SafeHttp
import fr.podcast.server.feeds.UrlGuard
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.awt.Color
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.time.Duration.Companion.hours
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Podcast artwork as this server serves it (ADR 0013): the feed's image, fetched once, cut square
 * and scaled down to [SIZE] pixels, kept as a JPEG under `<media directory>/artwork/`. Browsers
 * then only ask this server: neither a content blocker nor the image host's hotlink protection
 * can hide it, and a 3000-pixel image is not loaded for a 56-pixel tile.
 */
class ArtworkService(
    client: HttpClient,
    guard: UrlGuard,
    mediaDirectory: Path,
    private val maxBytes: Long = MAX_BYTES,
) {
    private val log = LoggerFactory.getLogger(ArtworkService::class.java)
    private val http = SafeHttp(client, guard)
    private val directory = mediaDirectory.toAbsolutePath().normalize().resolve("artwork")
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** Images that could not be had: not asked for again before [RETRY_AFTER]. */
    private val failures = ConcurrentHashMap<String, TimeMark>()

    /** The artwork made from the image at [url], on first use; null when it cannot be had. */
    suspend fun thumbnail(url: String): Path? {
        val key = key(url)
        val file = directory.resolve("$key.jpg")
        if (Files.exists(file)) return file
        // One download per image, however many pages ask for it at once.
        return locks.computeIfAbsent(key) { Mutex() }.withLock {
            when {
                Files.exists(file) -> file
                failures[key]?.let { it.elapsedNow() < RETRY_AFTER } == true -> null
                else -> try {
                    val bytes = download(url)
                    withContext(Dispatchers.IO) { write(decode(bytes), file) }
                    failures.remove(key)
                    file
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    log.info("No artwork from {}: {}", url, failure.message)
                    failures[key] = TimeSource.Monotonic.markNow()
                    null
                }
            }
        }
    }

    private suspend fun download(url: String): ByteArray = http.get(
        url,
        configure = {
            header(HttpHeaders.UserAgent, SafeHttp.USER_AGENT)
            // What ImageIO reads: some hosts would otherwise answer WebP or AVIF.
            header(HttpHeaders.Accept, "image/jpeg, image/png, image/gif;q=0.8")
            timeout {
                requestTimeoutMillis = 30_000
                socketTimeoutMillis = 15_000
            }
        },
    ) { response, finalUrl, _ ->
        if (!response.status.isSuccess()) throw FeedException.Unreachable("HTTP ${response.status.value} for $finalUrl")
        withContext(Dispatchers.IO) {
            LimitedInputStream(response.bodyAsChannel().toInputStream(), maxBytes).use { it.readBytes() }
        }
    }

    /**
     * The centered square of the image, at [SIZE] pixels. It is read with subsampling (one pixel
     * in n, down to about twice [SIZE]): a large image is never decoded whole.
     */
    private fun decode(bytes: ByteArray): BufferedImage {
        val input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes)) ?: error("Unreadable image")
        input.use {
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: error("Not an image")
            try {
                reader.input = input
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                require(width in 1..MAX_SIDE && height in 1..MAX_SIDE) { "Image of $width × $height pixels" }
                val side = minOf(width, height)
                val step = maxOf(1, side / (2 * SIZE))
                val param = reader.defaultReadParam.apply {
                    sourceRegion = Rectangle((width - side) / 2, (height - side) / 2, side, side)
                    setSourceSubsampling(step, step, 0, 0)
                }
                return scale(reader.read(0, param))
            } finally {
                reader.dispose()
            }
        }
    }

    /** Halved while it stays larger than [SIZE], then drawn at [SIZE]: smoother than a single step. */
    private fun scale(image: BufferedImage): BufferedImage {
        var current = image
        while (current.width / 2 >= SIZE && current.height / 2 >= SIZE) current = draw(current, current.width / 2, current.height / 2)
        return draw(current, SIZE, SIZE)
    }

    private fun draw(image: BufferedImage, width: Int, height: Int): BufferedImage =
        BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { target ->
            val graphics = target.createGraphics()
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                // JPEG has no transparency: a transparent image goes on white.
                graphics.color = Color.WHITE
                graphics.fillRect(0, 0, width, height)
                graphics.drawImage(image, 0, 0, width, height, null)
            } finally {
                graphics.dispose()
            }
        }

    /** Written beside, then moved: a request never reads half a file. */
    private fun write(image: BufferedImage, file: Path) {
        Files.createDirectories(file.parent)
        val partial = file.resolveSibling("${file.fileName}.part")
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        try {
            Files.newOutputStream(partial).use { output ->
                ImageIO.createImageOutputStream(output).use { stream ->
                    writer.output = stream
                    val param = writer.defaultWriteParam.apply {
                        compressionMode = ImageWriteParam.MODE_EXPLICIT
                        compressionQuality = QUALITY
                    }
                    writer.write(null, IIOImage(image, null, null), param)
                }
            }
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            writer.dispose()
            Files.deleteIfExists(partial)
        }
    }

    /** The file name of an image: its address, hashed. */
    private fun key(url: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(url.toByteArray()))

    companion object {
        /** Side of the artwork, in pixels: twice the largest tile of the thin client, for high-density screens. */
        const val SIZE = 256

        /** The largest image fetched. */
        const val MAX_BYTES = 10L * 1024 * 1024

        /** Larger images are refused: no podcast directory accepts them. */
        private const val MAX_SIDE = 12_000
        private const val QUALITY = 0.85f
        private val RETRY_AFTER = 1.hours

        init {
            // Drawn without a screen: on macOS, AWT would otherwise start the application's Dock icon.
            System.setProperty("java.awt.headless", "true")
            // Read in memory rather than through temporary files.
            ImageIO.setUseCache(false)
        }
    }
}
