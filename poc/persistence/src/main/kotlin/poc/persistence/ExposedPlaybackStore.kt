package poc.persistence

import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert

// Table objects mirror the Flyway migration by hand: nothing checks that they match.
object Podcasts : LongIdTable("podcasts") {
    val title = text("title")
    val feedUrl = text("feed_url").uniqueIndex()
}

object Episodes : LongIdTable("episodes") {
    val podcast = reference("podcast_id", Podcasts, onDelete = ReferenceOption.CASCADE)
    val title = text("title")
    val publishedOn = date("published_on")
    val durationSeconds = integer("duration_seconds")
}

object PlaybackStates : Table("playback_states") {
    val userId = long("user_id")
    val episode = reference("episode_id", Episodes, onDelete = ReferenceOption.CASCADE)
    val positionSeconds = integer("position_seconds").default(0)
    val status = text("status")
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestampWithTimeZone)
    override val primaryKey = PrimaryKey(userId, episode)
}

class ExposedPlaybackStore(private val db: Database) : PlaybackStore {

    override fun savePosition(userId: Long, episodeId: Long, positionSeconds: Int, status: String) {
        transaction(db) {
            PlaybackStates.upsert(
                PlaybackStates.userId, PlaybackStates.episode,
                onUpdate = {
                    it[PlaybackStates.positionSeconds] = positionSeconds
                    it[PlaybackStates.status] = status
                    it[PlaybackStates.updatedAt] = CurrentTimestampWithTimeZone
                },
            ) {
                it[PlaybackStates.userId] = userId
                it[episode] = episodeId
                it[PlaybackStates.positionSeconds] = positionSeconds
                it[PlaybackStates.status] = status
            }
        }
    }

    override fun inProgress(userId: Long): List<EpisodeRow> = transaction(db) {
        (PlaybackStates innerJoin Episodes innerJoin Podcasts)
            .select(Episodes.title, Podcasts.title, Episodes.durationSeconds, PlaybackStates.positionSeconds, PlaybackStates.status)
            .where { (PlaybackStates.userId eq userId) and (PlaybackStates.status eq "IN_PROGRESS") }
            .orderBy(PlaybackStates.updatedAt, SortOrder.DESC)
            .map {
                EpisodeRow(it[Episodes.title], it[Podcasts.title], it[Episodes.durationSeconds], it[PlaybackStates.positionSeconds], it[PlaybackStates.status])
            }
    }

    override fun latestWithState(userId: Long, limit: Int): List<EpisodeRow> = transaction(db) {
        (Episodes innerJoin Podcasts)
            .join(PlaybackStates, JoinType.LEFT, Episodes.id, PlaybackStates.episode) { PlaybackStates.userId eq userId }
            .select(Episodes.title, Podcasts.title, Episodes.durationSeconds, PlaybackStates.positionSeconds, PlaybackStates.status)
            .orderBy(Episodes.publishedOn to SortOrder.DESC, Episodes.id to SortOrder.DESC)
            .limit(limit)
            .map {
                EpisodeRow(
                    it[Episodes.title], it[Podcasts.title], it[Episodes.durationSeconds],
                    it.getOrNull(PlaybackStates.positionSeconds) ?: 0,
                    it.getOrNull(PlaybackStates.status) ?: "NEW",
                )
            }
    }
}
