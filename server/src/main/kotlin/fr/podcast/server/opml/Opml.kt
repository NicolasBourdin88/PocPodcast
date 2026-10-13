package fr.podcast.server.opml

import fr.podcast.shared.model.Podcast
import java.io.InputStream
import java.io.StringWriter
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException

/** One feed found in an OPML file. */
data class OpmlEntry(val feedUrl: String, val title: String)

class OpmlException(message: String) : Exception(message)

/** OPML 2.0, the format podcast apps use to move subscriptions (AntennaPod, Apple Podcasts…). */
object Opml {

    const val MAX_ENTRIES = 1000

    private val inputs: XMLInputFactory = XMLInputFactory.newFactory().apply {
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
    }

    /** Every outline with an `xmlUrl`, at any depth (apps often group feeds in categories). */
    fun read(input: InputStream): List<OpmlEntry> {
        val entries = mutableListOf<OpmlEntry>()
        try {
            val reader = inputs.createXMLStreamReader(input)
            var root: String? = null
            while (reader.hasNext()) {
                if (reader.next() != XMLStreamConstants.START_ELEMENT) continue
                if (root == null) {
                    root = reader.localName
                    if (root != "opml") throw OpmlException("Root element is <$root>, not <opml>")
                }
                if (reader.localName == "outline") {
                    val url = reader.getAttributeValue(null, "xmlUrl")?.trim()
                    if (!url.isNullOrEmpty()) {
                        val title = reader.getAttributeValue(null, "title") ?: reader.getAttributeValue(null, "text")
                        entries += OpmlEntry(url, title?.trim().orEmpty())
                        if (entries.size > MAX_ENTRIES) throw OpmlException("More than $MAX_ENTRIES feeds")
                    }
                }
            }
            reader.close()
            if (root == null) throw OpmlException("Empty document")
        } catch (malformed: XMLStreamException) {
            throw OpmlException("Malformed XML: ${malformed.message}")
        }
        return entries.distinctBy { it.feedUrl }
    }

    fun write(title: String, podcasts: List<Podcast>): String {
        val out = StringWriter()
        val xml = XMLOutputFactory.newFactory().createXMLStreamWriter(out)
        xml.writeStartDocument("UTF-8", "1.0")
        xml.writeCharacters("\n")
        xml.writeStartElement("opml")
        xml.writeAttribute("version", "2.0")
        xml.writeCharacters("\n  ")
        xml.writeStartElement("head")
        xml.writeStartElement("title")
        xml.writeCharacters(title)
        xml.writeEndElement()
        xml.writeStartElement("dateCreated")
        xml.writeCharacters(DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC)))
        xml.writeEndElement()
        xml.writeEndElement()
        xml.writeCharacters("\n  ")
        xml.writeStartElement("body")
        for (podcast in podcasts) {
            xml.writeCharacters("\n    ")
            xml.writeEmptyElement("outline")
            xml.writeAttribute("type", "rss")
            xml.writeAttribute("text", podcast.title)
            xml.writeAttribute("title", podcast.title)
            xml.writeAttribute("xmlUrl", podcast.feedUrl)
            podcast.websiteUrl?.let { xml.writeAttribute("htmlUrl", it) }
        }
        xml.writeCharacters("\n  ")
        xml.writeEndElement()
        xml.writeCharacters("\n")
        xml.writeEndElement()
        xml.writeEndDocument()
        xml.close()
        return out.toString() + "\n"
    }
}
