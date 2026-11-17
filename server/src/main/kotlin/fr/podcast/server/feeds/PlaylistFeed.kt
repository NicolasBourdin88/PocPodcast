package fr.podcast.server.feeds

import fr.podcast.shared.home.EpisodeItem
import fr.podcast.shared.model.Playlist
import java.io.StringWriter
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamWriter
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/** What a feed says of an episode's audio: its id in the original feed, its type and its size. */
data class Enclosure(val guid: String, val type: String, val length: Long)

/**
 * The RSS 2.0 feed of a playlist (ADR 0010), readable by any podcast app. Its enclosures point to
 * the server, which serves its copy of the file or sends the app to the publisher's.
 */
object PlaylistFeed {
    private const val ITUNES = "http://www.itunes.com/dtds/podcast-1.0.dtd"
    private const val ATOM = "http://www.w3.org/2005/Atom"

    /**
     * [feedUrl]: the feed's own absolute address; [siteUrl]: the server's; [enclosureUrl] gives the
     * absolute address of an episode's audio through this feed.
     */
    fun write(
        playlist: Playlist,
        ownerName: String,
        feedUrl: String,
        siteUrl: String,
        items: List<Pair<EpisodeItem, Enclosure>>,
        enclosureUrl: (episodeId: String) -> String,
    ): String {
        val out = StringWriter()
        val xml = XMLOutputFactory.newFactory().createXMLStreamWriter(out)
        xml.writeStartDocument("UTF-8", "1.0")
        xml.writeStartElement("rss")
        xml.writeAttribute("version", "2.0")
        xml.writeNamespace("itunes", ITUNES)
        xml.writeNamespace("atom", ATOM)
        xml.writeStartElement("channel")
        xml.text("title", playlist.title)
        xml.text("link", siteUrl)
        xml.text("description", playlist.description ?: playlist.title)
        xml.text("generator", "Podcast")
        xml.writeEmptyElement("atom", "link", ATOM)
        xml.writeAttribute("href", feedUrl)
        xml.writeAttribute("rel", "self")
        xml.writeAttribute("type", "application/rss+xml")
        xml.writeStartElement("itunes", "author", ITUNES)
        xml.writeCharacters(ownerName)
        xml.writeEndElement()
        items.firstNotNullOfOrNull { it.first.podcast.artworkUrl }?.let { artwork ->
            xml.writeEmptyElement("itunes", "image", ITUNES)
            xml.writeAttribute("href", artwork)
        }
        for ((item, enclosure) in items) {
            val episode = item.episode
            xml.writeStartElement("item")
            xml.text("title", episode.title)
            xml.writeStartElement("guid")
            xml.writeAttribute("isPermaLink", "false")
            xml.writeCharacters(enclosure.guid)
            xml.writeEndElement()
            xml.text("pubDate", rfc1123(episode.publishedAt))
            xml.text("description", episode.description)
            xml.writeEmptyElement("enclosure")
            xml.writeAttribute("url", enclosureUrl(episode.id))
            xml.writeAttribute("length", enclosure.length.toString())
            xml.writeAttribute("type", enclosure.type)
            if (episode.durationSeconds > 0) {
                xml.writeStartElement("itunes", "duration", ITUNES)
                xml.writeCharacters(episode.durationSeconds.toString())
                xml.writeEndElement()
            }
            xml.writeStartElement("itunes", "author", ITUNES)
            xml.writeCharacters(item.podcast.title)
            xml.writeEndElement()
            item.podcast.artworkUrl?.let { artwork ->
                xml.writeEmptyElement("itunes", "image", ITUNES)
                xml.writeAttribute("href", artwork)
            }
            xml.writeEndElement()
        }
        xml.writeEndElement()
        xml.writeEndElement()
        xml.writeEndDocument()
        xml.close()
        return out.toString() + "\n"
    }

    private fun XMLStreamWriter.text(name: String, value: String) {
        writeStartElement(name)
        writeCharacters(value)
        writeEndElement()
    }

    private fun rfc1123(instant: Instant): String =
        DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.ofInstant(instant.toJavaInstant(), ZoneOffset.UTC))
}
