package fr.podcast.server.catalog

import fr.podcast.server.auth.UserAccount
import fr.podcast.server.auth.access
import fr.podcast.server.db.PodcastDb
import fr.podcast.server.db.ServerDatabase
import fr.podcast.server.db.toKotlinInstant
import fr.podcast.shared.home.EpisodeItem
import fr.podcast.shared.model.Access
import fr.podcast.shared.model.Playlist
import fr.podcast.shared.model.PlaylistKind
import fr.podcast.shared.model.PlaylistOrder
import fr.podcast.shared.model.PlaylistRules
import fr.podcast.shared.model.SharePermission
import fr.podcast.shared.model.ShareType
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.util.Base64

/**
 * A playlist as one user sees it: whose it is, what that user was given, and what only the server
 * keeps (the order of a shuffle, the secret of its feed).
 */
data class PlaylistView(
    val playlist: Playlist,
    val ownerId: Long,
    val permission: SharePermission?,
    val shuffleSeed: Int,
    val feedSecret: String?,
) {
    val id: Long get() = playlist.id.toLong()
    val rules: PlaylistRules? get() = playlist.rules
}

/**
 * Playlists (ADR 0010). A static playlist keeps its episodes in the order its users chose; a
 * dynamic one is computed on its owner's subscriptions and marks, whoever reads it. Rights are
 * checked with `can` by the routes.
 */
class Playlists(private val database: ServerDatabase) {

    private val random = SecureRandom()

    /** The playlist if [viewer] may read it, with their access; null otherwise. */
    suspend fun view(viewer: UserAccount, id: String): PlaylistView? {
        val playlistId = id.toLongOrNull() ?: return null
        val row = database.query { playlistsQueries.playlistById(playlistId, ::PlaylistRow).executeAsOneOrNull() } ?: return null
        return viewOf(row, viewer)
    }

    /** [owner]'s playlists, by title. */
    suspend fun playlistsOf(owner: UserAccount): List<PlaylistView> =
        database.query { playlistsQueries.playlistsOfOwner(owner.id, ::PlaylistRow).executeAsList() }.mapNotNull { viewOf(it, owner) }

    /** The playlist of a published feed, as its owner sees it. */
    suspend fun byFeedSecret(secret: String): PlaylistView? {
        val row = database.query { playlistsQueries.playlistByFeedSecret(secret, ::PlaylistRow).executeAsOneOrNull() } ?: return null
        return row.toView(Access.OWNER, permission = null, ownerName = null, episodeCount = count(row))
    }

    /** The episodes of the playlist, with [viewerId]'s own state of each. */
    suspend fun episodes(view: PlaylistView, viewerId: Long): List<EpisodeItem> = database.query {
        val rules = view.rules
        if (rules == null) {
            episodesQueries.staticPlaylistEpisodes(viewerId, view.id, ::episodeItem).executeAsList()
        } else {
            dynamicEpisodes(view.ownerId, viewerId, rules, view.shuffleSeed)
        }
    }

    suspend fun create(ownerId: Long, title: String, description: String?, kind: PlaylistKind, rules: PlaylistRules): Long =
        database.query {
            playlistsQueries.insertPlaylist(
                ownerId, title, description, kind.name, rules.keywords.joinToString(", "), rules.favoritesOnly, rules.minRating,
                rules.unplayedOnly, rules.order.name, rules.limit, newSeed(),
            ).executeAsOne()
        }

    suspend fun update(id: Long, title: String, description: String?, rules: PlaylistRules) {
        database.query {
            playlistsQueries.updatePlaylist(
                title, description, rules.keywords.joinToString(", "), rules.favoritesOnly, rules.minRating, rules.unplayedOnly,
                rules.order.name, rules.limit, id,
            )
        }
    }

    /** The playlist goes with its items, its feed and its shares. */
    suspend fun delete(id: Long) {
        database.transaction {
            sharesQueries.deleteSharesOfResource(ShareType.PLAYLIST.name, id)
            playlistsQueries.deletePlaylist(id)
        }
    }

    /** At [position] (0 = first), or last when null; an episode already there moves. False when the episode does not exist. */
    suspend fun add(playlistId: Long, episodeId: String, position: Int?): Boolean {
        val id = episodeId.toLongOrNull() ?: return false
        return database.transaction {
            if (queueQueries.episodeExists(id).executeAsOne() == 0L) return@transaction false
            val ids = playlistsQueries.playlistItemIds(playlistId).executeAsList().toMutableList()
            val present = ids.remove(id)
            ids.add(position?.coerceIn(0, ids.size) ?: ids.size, id)
            if (!present) playlistsQueries.insertPlaylistItem(playlistId, id, ids.indexOf(id))
            renumber(playlistId, ids)
            true
        }
    }

    /** False when the episode is not in the playlist. */
    suspend fun move(playlistId: Long, episodeId: String, position: Int): Boolean {
        val id = episodeId.toLongOrNull() ?: return false
        return database.transaction {
            val ids = playlistsQueries.playlistItemIds(playlistId).executeAsList().toMutableList()
            if (!ids.remove(id)) return@transaction false
            ids.add(position.coerceIn(0, ids.size), id)
            renumber(playlistId, ids)
            true
        }
    }

    /** False when the episode is not in the playlist. */
    suspend fun remove(playlistId: Long, episodeId: String): Boolean {
        val id = episodeId.toLongOrNull() ?: return false
        return database.transaction {
            val ids = playlistsQueries.playlistItemIds(playlistId).executeAsList().toMutableList()
            if (!ids.remove(id)) return@transaction false
            playlistsQueries.deletePlaylistItem(playlistId, id)
            renumber(playlistId, ids)
            true
        }
    }

    /** A new random order for a RANDOM dynamic playlist. */
    suspend fun shuffle(playlistId: Long) {
        database.query { playlistsQueries.setShuffleSeed(newSeed(), playlistId) }
    }

    /** Publishes the feed under a new secret address; the previous address stops working. Returns the secret. */
    suspend fun publishFeed(playlistId: Long): String {
        val secret = ByteArray(24).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        database.query { playlistsQueries.setFeedSecret(playlistId, secret) }
        return secret
    }

    suspend fun unpublishFeed(playlistId: Long) {
        database.query { playlistsQueries.deleteFeed(playlistId) }
    }

    private suspend fun viewOf(row: PlaylistRow, viewer: UserAccount): PlaylistView? {
        val permission = if (row.ownerId == viewer.id) {
            null
        } else {
            database.query { sharesQueries.permissionOf(ShareType.PLAYLIST.name, row.id, viewer.id).executeAsOneOrNull() }
                ?.let(SharePermission::valueOf)
        }
        val access = viewer.access(row.ownerId, permission) ?: return null
        return row.toView(access, permission, ownerName = row.ownerName.takeIf { row.ownerId != viewer.id }, episodeCount = count(row))
    }

    /** The number of items of a static playlist, of computed episodes of a dynamic one. */
    private suspend fun count(row: PlaylistRow): Int =
        if (row.rules == null) row.itemCount else database.query { dynamicEpisodes(row.ownerId, row.ownerId, row.rules, row.shuffleSeed).size }

    private fun PodcastDb.dynamicEpisodes(ownerId: Long, viewerId: Long, rules: PlaylistRules, seed: Int): List<EpisodeItem> =
        episodesQueries.dynamicPlaylistEpisodes(
            ownerId = ownerId,
            viewerId = viewerId,
            keywordPattern = keywordPattern(rules.keywords),
            favoritesOnly = rules.favoritesOnly,
            minRating = rules.minRating,
            unplayedOnly = rules.unplayedOnly,
            sortOrder = rules.order.name,
            seed = seed.toLong(),
            limit = rules.limit.toLong(),
            mapper = ::episodeItem,
        ).executeAsList()

    private fun PodcastDb.renumber(playlistId: Long, ids: List<Long>) {
        ids.forEachIndexed { index, id -> playlistsQueries.setPlaylistItemPosition(index, playlistId, id) }
        playlistsQueries.touchPlaylist(playlistId)
    }

    /** A multiplier for the RANDOM order: any value from 1 to 1000002 gives another order of the episodes. */
    private fun newSeed(): Int = random.nextInt(1_000_002) + 1
}

/** One case-insensitive regular expression matching any of [keywords], their special characters escaped. */
internal fun keywordPattern(keywords: List<String>): String =
    keywords.joinToString("|") { keyword -> keyword.replace(REGEX_SPECIAL) { "\\" + it.value } }

private val REGEX_SPECIAL = Regex("""[\\.^$|?*+()\[\]{}]""")

/** A row of Playlists.sq, before the viewer's access is known. */
private class PlaylistRow(
    val id: Long,
    val ownerId: Long,
    val ownerName: String,
    val title: String,
    val description: String?,
    kind: String,
    keywords: String,
    favoritesOnly: Boolean,
    minRating: Int,
    unplayedOnly: Boolean,
    sortOrder: String,
    maxEpisodes: Int,
    val shuffleSeed: Int,
    createdAt: OffsetDateTime,
    val feedSecret: String?,
    itemCount: Long,
) {
    val createdAt = createdAt.toKotlinInstant()
    val itemCount = itemCount.toInt()
    val rules: PlaylistRules? = if (PlaylistKind.valueOf(kind) == PlaylistKind.STATIC) {
        null
    } else {
        PlaylistRules(
            keywords = PlaylistRules.parseKeywords(keywords),
            favoritesOnly = favoritesOnly,
            minRating = minRating,
            unplayedOnly = unplayedOnly,
            order = PlaylistOrder.valueOf(sortOrder),
            limit = maxEpisodes,
        )
    }

    fun toView(access: Access, permission: SharePermission?, ownerName: String?, episodeCount: Int) = PlaylistView(
        playlist = Playlist(
            id = id.toString(),
            title = title,
            description = description,
            kind = if (rules == null) PlaylistKind.STATIC else PlaylistKind.DYNAMIC,
            rules = rules,
            episodeCount = episodeCount,
            access = access,
            ownerName = ownerName,
            createdAt = createdAt,
        ),
        ownerId = ownerId,
        permission = permission,
        shuffleSeed = shuffleSeed,
        feedSecret = feedSecret,
    )
}
