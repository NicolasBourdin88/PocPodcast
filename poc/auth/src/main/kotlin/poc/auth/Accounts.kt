package poc.auth

import com.password4j.Password
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

data class User(val id: Long, val name: String, val passwordHash: String, val admin: Boolean = false)

/** An opaque bearer token. Only its SHA-256 is stored, so a database leak does not leak tokens. */
data class Token(val hash: String, val userId: Long, val device: String, val createdAt: Instant, var revoked: Boolean = false)

class Accounts {
    private val users = mutableMapOf<String, User>()
    private val tokens = mutableMapOf<String, Token>()
    private val random = SecureRandom()

    fun register(name: String, password: String, admin: Boolean = false): User {
        // Argon2id with a random salt (password4j defaults follow OWASP recommendations).
        val hash = Password.hash(password).addRandomSalt().withArgon2().result
        return User(users.size + 1L, name, hash, admin).also { users[name] = it }
    }

    fun user(id: Long): User? = users.values.firstOrNull { it.id == id }

    fun checkPassword(name: String, password: String): User? =
        users[name]?.takeIf { Password.check(password, it.passwordHash).withArgon2() }

    /** Issues a token for one device; the clear value is returned once and never stored. */
    fun issueToken(user: User, device: String): String {
        val value = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        tokens[sha256(value)] = Token(sha256(value), user.id, device, Instant.now())
        return value
    }

    fun userForToken(value: String): User? =
        tokens[sha256(value)]?.takeUnless { it.revoked }?.let { user(it.userId) }

    fun revoke(value: String) {
        tokens[sha256(value)]?.revoked = true
    }

    /** gPodder clients only speak HTTP Basic: accept the real password or a device token ("app password"). */
    fun checkBasic(name: String, secret: String): User? =
        userForToken(secret)?.takeIf { it.name == name } ?: checkPassword(name, secret)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
