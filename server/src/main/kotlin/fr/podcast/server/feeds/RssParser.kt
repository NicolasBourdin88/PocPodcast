package fr.podcast.server.feeds

import fr.podcast.shared.model.Transcript
import java.io.InputStream
import java.time.Instant as JavaInstant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader
import kotlin.time.Instant

data class ParsedFeed(
    val title: String,
    val author: String,
    val description: String,
    val artworkUrl: String?,
    val websiteUrl: String?,
    val language: String?,
    /** `itunes:new-feed-url`: the publisher moved the feed. */
    val newFeedUrl: String?,
    val items: List<ParsedItem>,
)

data class ParsedItem(
    val guid: String,
    val title: String,
    val description: String,
    val publishedAt: Instant,
    val durationSeconds: Int,
    val audioUrl: String,
    val audioType: String?,
    val audioLength: Long?,
    val chaptersUrl: String?,
    val chaptersType: String?,
    val transcripts: List<Transcript>,
)

/**
 * RSS 2.0 reader with the iTunes and Podcast 2.0 namespaces, streaming (StAX) so that
 * large feeds (several MB) are not loaded as a tree. DTDs are disabled: no external entities (XXE).
 */
object RssParser {

    private const val ITUNES = "http://www.itunes.com/dtds/podcast-1.0.dtd"
    private const val CONTENT = "http://purl.org/rss/1.0/modules/content/"
    private val PODCAST = setOf("https://podcastindex.org/namespace/1.0", "http://podcastindex.org/namespace/1.0")

    private const val MAX_DESCRIPTION = 10_000

    private val factory: XMLInputFactory = XMLInputFactory.newFactory().apply {
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true)
        setProperty(XMLInputFactory.IS_COALESCING, true)
    }

    /** @throws FeedException.NotAFeed if the document is not an RSS feed. */
    fun parse(input: InputStream, fetchedAt: Instant = Instant.fromEpochMilliseconds(System.currentTimeMillis())): ParsedFeed =
        try {
            val reader = factory.createXMLStreamReader(input)
            try {
                ChannelReader(reader, fetchedAt).read()
            } finally {
                reader.close()
            }
        } catch (malformed: XMLStreamException) {
            throw FeedException.NotAFeed("Malformed XML: ${malformed.message}")
        }

    private class ChannelReader(private val reader: XMLStreamReader, private val fetchedAt: Instant) {
        private var title: String? = null
        private var author = ""
        private var description = ""
        private var summary = ""
        private var artwork: String? = null
        private var imageUrl: String? = null
        private var website: String? = null
        private var language: String? = null
        private var newFeedUrl: String? = null
        private val items = mutableListOf<ParsedItem>()

        fun read(): ParsedFeed {
            if (!reader.nextStartElement() || reader.namespaceURI.orEmpty().isNotEmpty() || reader.localName != "rss") {
                throw FeedException.NotAFeed("Root element is not <rss>")
            }
            var sawChannel = false
            while (reader.nextStartElement()) {
                if (reader.localName == "channel" && reader.namespaceURI.isNullOrEmpty()) {
                    sawChannel = true
                    readChannel()
                } else {
                    reader.skipElement()
                }
            }
            val feedTitle = title?.takeIf { sawChannel && it.isNotBlank() } ?: throw FeedException.NotAFeed("No channel title")
            return ParsedFeed(
                title = feedTitle,
                author = author,
                description = description.ifBlank { summary },
                artworkUrl = artwork ?: imageUrl,
                websiteUrl = website,
                language = language,
                newFeedUrl = newFeedUrl,
                items = items,
            )
        }

        private fun readChannel() {
            reader.forEachChild { namespace, name ->
                when {
                    namespace.isEmpty() && name == "title" -> title = reader.readText()
                    namespace.isEmpty() && name == "link" -> website = reader.readText().ifBlank { null }
                    namespace.isEmpty() && name == "description" -> description = htmlToText(reader.readText())
                    namespace.isEmpty() && name == "language" -> language = reader.readText().ifBlank { null }
                    namespace.isEmpty() && name == "image" -> readImage()
                    namespace.isEmpty() && name == "item" -> readItem()?.let(items::add)
                    namespace == ITUNES && name == "author" -> author = reader.readText()
                    namespace == ITUNES && name == "summary" -> summary = htmlToText(reader.readText())
                    namespace == ITUNES && name == "image" -> {
                        artwork = reader.getAttributeValue(null, "href")?.ifBlank { null } ?: artwork
                        reader.skipElement()
                    }
                    namespace == ITUNES && name == "new-feed-url" -> newFeedUrl = reader.readText().ifBlank { null }
                    else -> reader.skipElement()
                }
            }
        }

        private fun readImage() {
            reader.forEachChild { namespace, name ->
                if (namespace.isEmpty() && name == "url") imageUrl = reader.readText().ifBlank { null } else reader.skipElement()
            }
        }

        /** Null for items without audio: they are not podcast episodes. */
        private fun readItem(): ParsedItem? {
            var guid: String? = null
            var itemTitle = ""
            var link: String? = null
            var itemDescription = ""
            var content = ""
            var itemSummary = ""
            var published: Instant? = null
            var duration = 0
            var audioUrl: String? = null
            var audioType: String? = null
            var audioLength: Long? = null
            var chaptersUrl: String? = null
            var chaptersType: String? = null
            val transcripts = mutableListOf<Transcript>()

            reader.forEachChild { namespace, name ->
                when {
                    namespace.isEmpty() && name == "guid" -> guid = reader.readText().ifBlank { null }
                    namespace.isEmpty() && name == "title" -> itemTitle = reader.readText()
                    namespace.isEmpty() && name == "link" -> link = reader.readText().ifBlank { null }
                    namespace.isEmpty() && name == "description" -> itemDescription = reader.readText()
                    namespace.isEmpty() && name == "pubDate" -> published = parseDate(reader.readText())
                    namespace.isEmpty() && name == "enclosure" -> {
                        audioUrl = reader.getAttributeValue(null, "url")?.trim()?.ifBlank { null }
                        audioType = reader.getAttributeValue(null, "type")?.trim()?.ifBlank { null }
                        audioLength = reader.getAttributeValue(null, "length")?.trim()?.toLongOrNull()?.takeIf { it > 0 }
                        reader.skipElement()
                    }
                    namespace == CONTENT && name == "encoded" -> content = reader.readText()
                    namespace == ITUNES && name == "summary" -> itemSummary = reader.readText()
                    namespace == ITUNES && name == "duration" -> duration = parseDuration(reader.readText())
                    namespace in PODCAST && name == "chapters" -> {
                        chaptersUrl = reader.getAttributeValue(null, "url")?.trim()?.ifBlank { null }
                        chaptersType = reader.getAttributeValue(null, "type")?.trim()?.ifBlank { null }
                        reader.skipElement()
                    }
                    namespace in PODCAST && name == "transcript" -> {
                        val url = reader.getAttributeValue(null, "url")?.trim()
                        val type = reader.getAttributeValue(null, "type")?.trim()
                        if (!url.isNullOrBlank() && !type.isNullOrBlank()) {
                            transcripts += Transcript(url, type, reader.getAttributeValue(null, "language")?.trim()?.ifBlank { null })
                        }
                        reader.skipElement()
                    }
                    else -> reader.skipElement()
                }
            }

            val audio = audioUrl ?: return null
            return ParsedItem(
                guid = guid ?: audio,
                title = itemTitle.ifBlank { link ?: audio },
                description = htmlToText(content.ifBlank { itemDescription }.ifBlank { itemSummary }),
                publishedAt = published ?: fetchedAt,
                durationSeconds = duration,
                audioUrl = audio,
                audioType = audioType,
                audioLength = audioLength,
                chaptersUrl = chaptersUrl,
                chaptersType = chaptersType,
                transcripts = transcripts.distinctBy { it.url },
            )
        }
    }

    // --- StAX helpers ---

    /** Moves to the next start element at any depth; false at the end of the document. */
    private fun XMLStreamReader.nextStartElement(): Boolean {
        while (hasNext()) {
            when (next()) {
                XMLStreamConstants.START_ELEMENT -> return true
                XMLStreamConstants.END_ELEMENT -> return false
            }
        }
        return false
    }

    /** Calls [block] on each child element of the current one; [block] must consume the child. */
    private inline fun XMLStreamReader.forEachChild(block: (namespace: String, name: String) -> Unit) {
        while (hasNext()) {
            when (next()) {
                XMLStreamConstants.START_ELEMENT -> block(namespaceURI.orEmpty(), localName)
                XMLStreamConstants.END_ELEMENT -> return
            }
        }
    }

    /** Text content of the current element, ignoring nested markup. Ends on its END_ELEMENT. */
    private fun XMLStreamReader.readText(): String {
        val text = StringBuilder()
        var depth = 1
        while (depth > 0 && hasNext()) {
            when (next()) {
                XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> text.append(this.text)
                XMLStreamConstants.START_ELEMENT -> depth++
                XMLStreamConstants.END_ELEMENT -> depth--
            }
        }
        return text.toString().trim()
    }

    private fun XMLStreamReader.skipElement() {
        var depth = 1
        while (depth > 0 && hasNext()) {
            when (next()) {
                XMLStreamConstants.START_ELEMENT -> depth++
                XMLStreamConstants.END_ELEMENT -> depth--
            }
        }
    }

    // --- Values ---

    private val zoneNames = mapOf(
        "UT" to "+0000", "UTC" to "+0000", "GMT" to "+0000", "Z" to "+0000",
        "EST" to "-0500", "EDT" to "-0400", "CST" to "-0600", "CDT" to "-0500",
        "MST" to "-0700", "MDT" to "-0600", "PST" to "-0800", "PDT" to "-0700",
        "CET" to "+0100", "CEST" to "+0200",
    )

    private val withZone = listOf("d MMM yyyy HH:mm:ss Z", "d MMM yyyy HH:mm Z", "d MMMM yyyy HH:mm:ss Z").map(::formatter)
    private val withoutZone = listOf("d MMM yyyy HH:mm:ss", "d MMM yyyy HH:mm").map(::formatter)

    private fun formatter(pattern: String): DateTimeFormatter =
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern).toFormatter(Locale.ENGLISH)

    /** RFC 822 dates as found in real feeds (zone names, missing seconds…), or ISO 8601. */
    fun parseDate(raw: String): Instant? {
        var value = raw.trim().replace(Regex("\\s+"), " ").replace(Regex("^[A-Za-z]+,?\\s*"), "")
        zoneNames.forEach { (name, offset) -> if (value.endsWith(" $name")) value = value.removeSuffix(name) + offset }
        val javaInstant = withZone.firstNotNullOfOrNull { runCatching { OffsetDateTime.parse(value, it).toInstant() }.getOrNull() }
            ?: withoutZone.firstNotNullOfOrNull { runCatching { LocalDateTime.parse(value, it).toInstant(ZoneOffset.UTC) }.getOrNull() }
            ?: runCatching { OffsetDateTime.parse(raw.trim()).toInstant() }.getOrNull()
            ?: runCatching { JavaInstant.parse(raw.trim()) }.getOrNull()
        return javaInstant?.let { Instant.fromEpochSeconds(it.epochSecond, it.nano) }
    }

    /** "3600", "1:00:00", "60:00" or "45:30.5" → seconds; 0 when unknown. */
    fun parseDuration(raw: String): Int {
        val value = raw.trim()
        if (value.isEmpty()) return 0
        val parts = value.split(':').map { it.trim().toDoubleOrNull() ?: return 0 }
        if (parts.size > 3 || parts.any { it < 0 }) return 0
        return parts.fold(0.0) { total, part -> total * 60 + part }.toInt()
    }

    private val entities = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")

    /** Show notes as plain text: tags removed, entities decoded, blank lines kept as paragraphs. */
    fun htmlToText(html: String): String {
        val withBreaks = html
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|div|li|h[1-6])>"), "\n")
            .replace(Regex("<[^>]*>"), "")
        val decoded = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);").replace(withBreaks) { match ->
            val code = match.groupValues[1]
            when {
                code.startsWith("#x") -> code.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                code.startsWith("#") -> code.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                else -> entities[code.lowercase()]
            } ?: match.value
        }
        return decoded.lines().map { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }
            .joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim().take(MAX_DESCRIPTION)
    }
}
