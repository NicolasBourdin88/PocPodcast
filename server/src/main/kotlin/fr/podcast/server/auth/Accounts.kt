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

data class UserAccount(
    val id: Long,
    val username: String,
    val displayName: String,
    val role: Role,
    /** Incremented by every new password: sessions carry it and expire with it. */
    val credentialsVersion: Int = 0,
    /** An [fr.podcast.shared.model.Avatar] id, none until the user chooses (ADR 0013). */
    val avatar: String? = null,
)

data class IssuedToken(val value: String, val deviceName: String, val createdAt: Instant)

/** A device signed in to an account, through its token. */
data class Device(val id: Long, val name: String, val createdAt: Instant, val lastUsedAt: Instant?, val current: Boolean)

/** Users, passwords (Argon2id) and per-device tokens (ADR 0005, 0009). */
class Accounts(private val database: ServerDatabase) {

    private val random = SecureRandom()

    // Compared against when the username does not exist, so both cases take the same time.
    private val dummyHash by lazy { hashPassword("not a real password") }

    suspend fun create(username: String, displayName: String, password: String, role: Role = Role.USER): UserAccount {
        val id = database.query { usersQueries.insertUser(username, displayName, hashPassword(password), role.name).executeAsOne() }
        return UserAccount(id, username, displayName, role)
    }

    suspend fun user(id: Long): UserAccount? = database.query { usersQueries.userById(id, ::account).executeAsOneOrNull() }

    suspend fun userByUsername(username: String): UserAccount? =
        database.query { usersQueries.userByUsername(username, ::account).executeAsOneOrNull() }

    suspend fun users(): List<UserAccount> = database.query { usersQueries.allUsers(::account).executeAsList() }

    suspend fun count(): Long = database.query { usersQueries.countUsers().executeAsOne() }

    /** The account if [password] is right, null otherwise. */
    suspend fun authenticate(username: String, password: String): UserAccount? {
        val row = database.query { usersQueries.userByUsername(username).executeAsOneOrNull() }
        val hash = row?.password_hash ?: dummyHash
        // Check with the parameters stored in the hash, not the configured ones (withArgon2()):
        // raising the Argon2 cost later must not lock out existing accounts.
        val matches = Password.check(password, hash).with(Argon2Function.getInstanceFromHash(hash))
        return row?.takeIf { matches }?.let { account(it.id, it.username, it.display_name, it.password_hash, it.role, it.credentials_version, it.avatar) }
    }

    suspend fun rename(userId: Long, displayName: String) {
        database.query { usersQueries.updateDisplayName(displayName, userId) }
    }

    suspend fun setAvatar(userId: Long, avatar: String) {
        database.query { usersQueries.updateAvatar(avatar, userId) }
    }

    /** False when it would leave the server without an administrator. */
    suspend fun setRole(userId: Long, role: Role): Boolean = database.transaction {
        val current = usersQueries.userById(userId, ::account).executeAsOneOrNull() ?: return@transaction false
        if (current.role == Role.ADMIN && role != Role.ADMIN && usersQueries.countAdmins().executeAsOne() <= 1L) return@transaction false
        usersQueries.updateRole(role.name, userId)
        true
    }

    /**
     * Sets a new password: the sessions opened before expire, and every device but the one
     * using [keepToken] must sign in again. Returns the new credentials version.
     */
    suspend fun setPassword(userId: Long, password: String, keepToken: String? = null): Int {
        val hash = hashPassword(password)
        return database.transaction {
            val version = usersQueries.updatePassword(hash, userId).executeAsOne()
            tokensQueries.revokeOtherDevices(userId, keepToken?.let(::hashToken) ?: "")
            version
        }
    }

    suspend fun delete(userId: Long) {
        database.query { usersQueries.deleteUser(userId) }
    }

    /** Creates a token for one device. Its clear value is returned once and never stored. */
    suspend fun issueToken(user: UserAccount, deviceName: String): IssuedToken {
        val value = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val createdAt = database.query { tokensQueries.insertToken(user.id, hashToken(value), deviceName).executeAsOne() }
        return IssuedToken(value, deviceName, createdAt.toKotlinInstant())
    }

    suspend fun userForToken(value: String): UserAccount? {
        val used = database.query { tokensQueries.useToken(hashToken(value)).executeAsOneOrNull() } ?: return null
        return user(used.user_id)
    }

    /**
     * For clients that only have a password field, such as gPodder ones (ADR 0011): [secret] is
     * the account's password or an app password, that is one of its device tokens, whose id
     * comes along. Null when neither matches [username].
     */
    suspend fun authenticateWithSecret(username: String, secret: String): Pair<UserAccount, Long?>? {
        if (TOKEN_FORMAT.matches(secret)) {
            database.query { tokensQueries.useToken(hashToken(secret)).executeAsOneOrNull() }?.let { used ->
                return user(used.user_id)?.takeIf { it.username == username }?.let { it to used.id }
            }
        }
        return authenticate(username, secret)?.let { it to null }
    }

    suspend fun tokenIsActive(id: Long): Boolean = database.query { tokensQueries.activeToken(id).executeAsOneOrNull() } != null

    suspend fun revokeToken(value: String) {
        database.query { tokensQueries.revokeToken(hashToken(value)) }
    }

    /** The devices signed in to the account; [currentToken] marks the one making the request. */
    suspend fun devices(userId: Long, currentToken: String?): List<Device> {
        val currentHash = currentToken?.let(::hashToken)
        return database.query {
            tokensQueries.devicesOfUser(userId) { id, name, createdAt, lastUsedAt, tokenHash ->
                Device(id, name, createdAt.toKotlinInstant(), lastUsedAt?.toKotlinInstant(), current = tokenHash == currentHash)
            }.executeAsList()
        }
    }

    /** False when the device does not exist or belongs to someone else. */
    suspend fun revokeDevice(userId: Long, deviceId: Long): Boolean =
        database.query { tokensQueries.revokeDevice(deviceId, userId).executeAsOneOrNull() } != null

    private fun hashPassword(password: String): String = Password.hash(password).addRandomSalt().withArgon2().result

    private fun hashToken(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** What issueToken produces: 32 random bytes in base64url, without padding. */
private val TOKEN_FORMAT = Regex("[A-Za-z0-9_-]{43}")

@Suppress("UNUSED_PARAMETER")
private fun account(id: Long, username: String, displayName: String, passwordHash: String, role: String, credentialsVersion: Int, avatar: String?) =
    UserAccount(id, username, displayName, Role.valueOf(role), credentialsVersion, avatar)
