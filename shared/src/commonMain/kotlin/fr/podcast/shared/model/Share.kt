package fr.podcast.shared.model

import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
enum class ShareType { SUBSCRIPTION, EPISODE, PLAYLIST }

/** READ: see and listen; WRITE: also modify (a subscription's note and automatic download). */
@Serializable
enum class SharePermission { READ, WRITE }

/** Something [owner] shares with [grantee] (ADR 0009); an episode is shared as a recommendation, READ only. */
@Serializable
data class Share(
    val id: String,
    val type: ShareType,
    val permission: SharePermission,
    /** A word from the owner ("listen from 12:00"). */
    val message: String? = null,
    val createdAt: Instant,
    val owner: User,
    val grantee: User,
)
