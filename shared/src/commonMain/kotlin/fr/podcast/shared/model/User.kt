package fr.podcast.shared.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
enum class UserRole { USER, ADMIN }

/** An account of the server, as other users see it (ADR 0009). */
@Serializable
data class User(
    val id: String,
    val username: String,
    val displayName: String,
    val role: UserRole = UserRole.USER,
    /** An [Avatar] id; none until the user chooses one (ADR 0013). */
    val avatar: String? = null,
) {
    /** The picture to show: the user's choice, or else their default one. */
    val picture: Avatar get() = Avatar.of(avatar, username)
}

/** Rules on accounts, checked by the server and by the clients before they send (ADR 0009). */
object AccountRules {
    const val MIN_PASSWORD_LENGTH = 8
    const val MAX_DISPLAY_NAME_LENGTH = 100
    private val USERNAME = Regex("[a-z0-9][a-z0-9._-]{2,31}")

    /** 3 to 32 characters: lowercase letters, digits, '.', '_' or '-', starting with a letter or a digit. */
    fun validUsername(username: String): Boolean = USERNAME.matches(username)

    /** Once trimmed: 1 to [MAX_DISPLAY_NAME_LENGTH] characters. */
    fun validDisplayName(displayName: String): Boolean = displayName.trim().length in 1..MAX_DISPLAY_NAME_LENGTH

    fun validPassword(password: String): Boolean = password.length >= MIN_PASSWORD_LENGTH
}

/** A device signed in to the current account (one token each, ADR 0005). */
@Serializable
data class Device(
    val id: String,
    val name: String,
    val createdAt: Instant,
    val lastUsedAt: Instant? = null,
    /** The device making the request. */
    val current: Boolean = false,
) {
    fun lastUsedOn(timeZone: TimeZone = TimeZone.currentSystemDefault()): LocalDate? = lastUsedAt?.toLocalDateTime(timeZone)?.date
}
