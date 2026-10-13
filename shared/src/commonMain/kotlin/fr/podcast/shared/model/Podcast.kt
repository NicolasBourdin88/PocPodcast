package fr.podcast.shared.model

import kotlinx.serialization.Serializable

@Serializable
data class Podcast(
    val id: String,
    val title: String,
    val author: String,
    val feedUrl: String,
    val artworkUrl: String? = null,
)
