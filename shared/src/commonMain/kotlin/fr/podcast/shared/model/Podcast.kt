package fr.podcast.shared.model

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** State of the server's last reading of the feed. */
@Serializable
enum class RefreshStatus { PENDING, OK, FAILED }

@Serializable
data class Podcast(
    val id: String,
    val title: String,
    val author: String,
    val feedUrl: String,
    val artworkUrl: String? = null,
    val description: String = "",
    val websiteUrl: String? = null,
    val language: String? = null,
    val refreshStatus: RefreshStatus = RefreshStatus.OK,
    val refreshedAt: Instant? = null,
)
