package poc.persistence

/** The three queries the Home screen needs, implemented once per candidate library. */
interface PlaybackStore {
    fun savePosition(userId: Long, episodeId: Long, positionSeconds: Int, status: String)
    fun inProgress(userId: Long): List<EpisodeRow>
    fun latestWithState(userId: Long, limit: Int): List<EpisodeRow>
}

data class EpisodeRow(
    val title: String,
    val podcastTitle: String,
    val durationSeconds: Int,
    val positionSeconds: Int,
    val status: String,
)
