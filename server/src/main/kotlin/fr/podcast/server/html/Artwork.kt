package fr.podcast.server.html

import fr.podcast.shared.design.artworkColor
import fr.podcast.shared.design.initials
import fr.podcast.shared.design.toCssHex
import fr.podcast.shared.home.EpisodeItem
import fr.podcast.shared.model.Podcast
import kotlinx.html.FlowContent
import kotlinx.html.aside
import kotlinx.html.img

/**
 * The podcast's artwork, as this server keeps it (`/podcasts/{id}/artwork`, ADR 0013); without an
 * image, its initials on its color, as in the apps.
 */
internal fun FlowContent.artwork(podcast: Podcast) {
    if (podcast.artworkUrl != null) {
        img(src = "/podcasts/${podcast.id}/artwork", alt = "", classes = "artwork") { attributes["loading"] = "lazy" }
    } else {
        aside("artwork") {
            attributes["style"] = "background: ${podcast.artworkColor.toCssHex()}"
            attributes["aria-hidden"] = "true"
            +podcast.initials
        }
    }
}

/** An episode shows its podcast's artwork. */
internal fun FlowContent.artwork(item: EpisodeItem) = artwork(item.podcast)

/**
 * What `/podcasts/{id}/artwork` answers when the feed's image cannot be had: the same initials
 * on the same color, drawn at the size of a tile. Initials are letters and digits: nothing to escape.
 */
fun artworkPlaceholderSvg(podcast: Podcast): String =
    """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 56 56" width="256" height="256">""" +
        """<rect width="56" height="56" fill="${podcast.artworkColor.toCssHex()}"/>""" +
        """<text x="28" y="28" dominant-baseline="central" text-anchor="middle" fill="#fff" """ +
        """font-family="system-ui, -apple-system, sans-serif" font-size="16" font-weight="700">${podcast.initials}</text></svg>"""
