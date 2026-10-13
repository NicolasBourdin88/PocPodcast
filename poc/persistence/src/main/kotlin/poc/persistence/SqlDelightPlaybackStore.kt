package poc.persistence

import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import poc.persistence.sqldelight.PocDatabase
import javax.sql.DataSource

// Queries live in Playback.sq; SQLDelight generated the typed functions below at build time.
class SqlDelightPlaybackStore(dataSource: DataSource) : PlaybackStore {

    private val queries = PocDatabase(dataSource.asJdbcDriver()).playbackQueries

    override fun savePosition(userId: Long, episodeId: Long, positionSeconds: Int, status: String) {
        queries.upsertState(userId, episodeId, positionSeconds, status)
    }

    override fun inProgress(userId: Long): List<EpisodeRow> =
        queries.inProgress(userId) { title, podcastTitle, duration, position ->
            EpisodeRow(title, podcastTitle, duration, position, "IN_PROGRESS")
        }.executeAsList()

    override fun latestWithState(userId: Long, limit: Int): List<EpisodeRow> =
        queries.latestWithState(userId, limit.toLong()) { title, podcastTitle, duration, position, status ->
            EpisodeRow(title, podcastTitle, duration, position, status)
        }.executeAsList()
}
