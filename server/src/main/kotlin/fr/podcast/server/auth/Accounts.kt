package fr.podcast.server.auth

import com.password4j.Argon2Function
import com.password4j.Password
import fr.podcast.server.db.ServerDatabase
import fr.podcast.server.db.toKotlinInstant
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Instant

enum class Role { USER, ADMIN }

data class UserAccount(val id: Long, val username: String, val displayName: String, val role: Role)

data class IssuedToken(val value: String, val deviceName: String, val createdAt: Instant)

/** Users, passwords (Argon2id) and per-device tokens (ADR 0005). */
class Accounts(private val database: ServerDatabase) {

    private val random = SecureRandom()

    // Compared against when the username does not exist, so both cases take the same time.
    private val dummyHash by lazy { hashPassword("not a real password") }

    suspend fun create(username: String, displayName: String, password: String, role: Role = Role.USER): UserAccount {
        val id = database.query { usersQueries.insertUser(username, displayName, hashPassword(password), role.name).executeAsOne() }
        return UserAccount(id, username, displayName, role)
    }

    suspend fun user(id: Long): UserAccount? = database.query {
        usersQueries.userById(id) { id, username, displayName, _, role -> UserAccount(id, username, displayName, Role.valueOf(role)) }
            .executeAsOneOrNull()
    }

    /** The account if [password] is right, null otherwise. */
    suspend fun authenticate(username: String, password: String): UserAccount? {
        val row = database.query { usersQueries.userByUsername(username).executeAsOneOrNull() }
        val hash = row?.password_hash ?: dummyHash
        // Check with the parameters stored in the hash, not the configured ones (withArgon2()):
        // raising the Argon2 cost later must not lock out existing accounts.
        val matches = Password.check(password, hash).with(Argon2Function.getInstanceFromHash(hash))
        return row?.takeIf { matches }?.let { UserAccount(it.id, it.username, it.display_name, Role.valueOf(it.role)) }
    }

    /** Creates a token for one device. Its clear value is returned once and never stored. */
    suspend fun issueToken(user: UserAccount, deviceName: String): IssuedToken {
        val value = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val createdAt = database.query { tokensQueries.insertToken(user.id, hashToken(value), deviceName).executeAsOne() }
        return IssuedToken(value, deviceName, createdAt.toKotlinInstant())
    }

    suspend fun userForToken(value: String): UserAccount? {
        val userId = database.query { tokensQueries.useToken(hashToken(value)).executeAsOneOrNull() } ?: return null
        return user(userId)
    }

    suspend fun revokeToken(value: String) {
        database.query { tokensQueries.revokeToken(hashToken(value)) }
    }

    private fun hashPassword(password: String): String = Password.hash(password).addRandomSalt().withArgon2().result

    private fun hashToken(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
