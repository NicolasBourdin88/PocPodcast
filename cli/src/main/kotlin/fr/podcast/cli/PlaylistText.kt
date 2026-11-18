package fr.podcast.cli

import fr.podcast.shared.i18n.Strings
import fr.podcast.shared.model.Access
import fr.podcast.shared.model.Episode
import fr.podcast.shared.model.Playlist
import fr.podcast.shared.model.SharePermission
import fr.podcast.shared.repository.PlaylistDetails
import fr.podcast.shared.repository.SharedItem

/** A playlist as `podcast playlists` lists it, with the owner's message when it is shared with the user. */
data class PlaylistEntry(val playlist: Playlist, val message: String? = null)

/** The user's own playlists, then those shared with them: the numbers the `playlists` subcommands use. */
fun numberedPlaylists(own: List<Playlist>, shared: List<SharedItem>): List<PlaylistEntry> =
    own.map { PlaylistEntry(it) } + shared.mapNotNull { item -> item.playlist?.let { PlaylistEntry(it, item.share.message) } }

/** Numbered playlists for `podcast playlists`. */
fun renderPlaylistsText(entries: List<PlaylistEntry>, strings: Strings): String = buildString {
    if (entries.isEmpty()) return strings.emptyPlaylists
    val firstShared = entries.indexOfFirst { it.playlist.ownerName != null }
    entries.forEachIndexed { index, entry ->
        if (index == firstShared) {
            if (index > 0) appendLine()
            appendLine(strings.sharedPlaylists)
        }
        appendLine("${number(index)} ${entry.playlist.title} — ${summary(entry.playlist, strings)}")
        entry.playlist.rules?.let { appendLine("    ${strings.playlistRules(it)}") }
        entry.message?.let { appendLine("    ✉ $it") }
    }
}.trimEnd()

/**
 * Playlist n° [position] for `podcast playlists show <n>`: what it is, its feed for the owner, and its
 * episodes, numbered for `playlists remove` and `playlists move`.
 */
fun renderPlaylistText(position: Int, details: PlaylistDetails, strings: Strings): String = buildString {
    val playlist = details.playlist
    appendLine("${number(position - 1)} ${playlist.title} — ${summary(playlist, strings)}")
    playlist.description?.let { appendLine("    $it") }
    playlist.rules?.let { appendLine("    ${strings.playlistRules(it)}") }
    playlist.feedUrl?.let { appendLine("    ${strings.feed} — $it") }
    appendLine()
    if (details.episodes.isEmpty()) appendLine(strings.emptyPlaylist)
    details.episodes.forEachIndexed { index, item ->
        val episode = item.episode
        appendLine("${number(index)} ${episode.title} — ${item.podcast.title}")
        appendLine("    ${strings.duration(episode.durationSeconds)} · ${strings.status(episode)}${marks(episode, strings)}")
    }
}.trimEnd()

/** "Static · 2 episodes", after whose it is and the user's rights when it is shared with them. */
private fun summary(playlist: Playlist, strings: Strings): String = listOfNotNull(
    playlist.ownerName?.let(strings::playlistSharedBy),
    playlist.ownerName?.let { strings.permission(if (playlist.access == Access.WRITE) SharePermission.WRITE else SharePermission.READ) },
    strings.playlistKind(playlist.kind),
    strings.episodeCount(playlist.episodeCount),
).joinToString(" · ")

/** " · ♥ · ★★★★☆": the user's favorite and rating, when they gave them. */
internal fun marks(episode: Episode, strings: Strings): String =
    listOfNotNull("♥".takeIf { episode.favorite }, episode.rating?.let(strings::stars)).joinToString("") { " · $it" }
