package fr.podcast.shared.model

import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
enum class UserRole { USER, ADMIN }

/** An account of the server, as other users see it (ADR 0009). */
@Serializable
data class User(val id: String, val username: String, val displayName: String, val role: UserRole = UserRole.USER)

/** A device signed in to the current account (one token each, ADR 0005). */
@Serializable
data class Device(
    val id: String,
    val name: String,
    val createdAt: Instant,
    val lastUsedAt: Instant? = null,
    /** The device making the request. */
    val current: Boolean = false,
)
