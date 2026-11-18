package fr.podcast.shared.repository

import fr.podcast.shared.api.ApiException
import fr.podcast.shared.api.FieldError
import fr.podcast.shared.model.AccountRules
import fr.podcast.shared.model.Device
import fr.podcast.shared.model.User
import fr.podcast.shared.model.UserRole
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant

/**
 * Accounts of the demo: the demo user administers a small server, so that every screen can be
 * tried. Refusals are those of the server (ADR 0009).
 */
internal class FakeAccountRepository : AccountRepository {

    private val mutex = Mutex()
    private var nextId = 10

    private val users = mutableListOf(
        User(ME, "demo", "Demo", UserRole.ADMIN),
        User(ALICE, "alice", "Alice"),
        User(BOB, "bob", "Bob"),
    )

    private val devices = mutableListOf(
        Device("d1", "MacBook Air", Instant.parse("2026-09-01T08:00:00Z"), Instant.parse("2026-10-12T07:30:00Z"), current = true),
        Device("d2", "Pixel 9 Pro", Instant.parse("2026-09-03T19:12:00Z"), Instant.parse("2026-10-11T18:05:00Z")),
        Device("d3", "Terminal", Instant.parse("2026-10-02T09:00:00Z")),
    )

    suspend fun user(id: String): User? = mutex.withLock { users.find { it.id == id } }

    override suspend fun me(): User = user(ME)!!

    override suspend fun updateProfile(displayName: String): User = mutex.withLock {
        val name = displayName.trim()
        if (!AccountRules.validDisplayName(name)) throw invalid("displayName")
        replace(ME) { it.copy(displayName = name) }
    }

    /** The demo has no password: only the new one is checked. */
    override suspend fun changePassword(currentPassword: String, newPassword: String) {
        if (!AccountRules.validPassword(newPassword)) throw invalid("newPassword")
        mutex.withLock { devices.retainAll { it.current } }
    }

    override suspend fun devices(): List<Device> = mutex.withLock { devices.toList() }

    override suspend fun revokeDevice(deviceId: String) {
        mutex.withLock { if (!devices.removeAll { it.id == deviceId }) throw ApiException.NotFound("device $deviceId") }
    }

    /** As the server: a random password, shown once, then one more device. */
    override suspend fun createAppPassword(name: String): String = mutex.withLock {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > 100) throw invalid("name")
        appPasswords++
        devices += Device("app$appPasswords", trimmed, kotlin.time.Clock.System.now())
        buildString { repeat(43) { append(PASSWORD_ALPHABET.random()) } }
    }

    private var appPasswords = 0

    override suspend fun users(): List<User> = mutex.withLock { users.sortedBy { it.displayName.lowercase() } }

    override suspend fun createUser(username: String, displayName: String, password: String, role: UserRole): User = mutex.withLock {
        val errors = buildList {
            if (!AccountRules.validUsername(username) || users.any { it.username == username }) add(FieldError("username", "invalid or taken"))
            if (!AccountRules.validDisplayName(displayName)) add(FieldError("displayName", "invalid"))
            if (!AccountRules.validPassword(password)) add(FieldError("password", "too short"))
        }
        if (errors.isNotEmpty()) throw ApiException.Rejected(422, null, "Invalid account", errors)
        User("u${nextId++}", username, displayName.trim(), role).also(users::add)
    }

    override suspend fun updateUser(userId: String, displayName: String?, role: UserRole?): User = mutex.withLock {
        val user = users.find { it.id == userId } ?: throw ApiException.NotFound("user $userId")
        if (displayName != null && !AccountRules.validDisplayName(displayName)) throw invalid("displayName")
        val lastAdmin = user.role == UserRole.ADMIN && users.count { it.role == UserRole.ADMIN } == 1
        if (role == UserRole.USER && lastAdmin) throw invalid("role")
        replace(userId) { it.copy(displayName = displayName?.trim() ?: it.displayName, role = role ?: it.role) }
    }

    override suspend fun resetPassword(userId: String, password: String) {
        if (user(userId) == null) throw ApiException.NotFound("user $userId")
        if (!AccountRules.validPassword(password)) throw invalid("password")
    }

    override suspend fun deleteUser(userId: String) {
        if (userId == ME) throw ApiException.Rejected(403, null, "An administrator cannot delete their own account")
        mutex.withLock { if (!users.removeAll { it.id == userId }) throw ApiException.NotFound("user $userId") }
    }

    private fun replace(id: String, change: (User) -> User): User {
        val index = users.indexOfFirst { it.id == id }
        return change(users[index]).also { users[index] = it }
    }

    private fun invalid(field: String) = ApiException.Rejected(422, null, "Invalid $field", listOf(FieldError(field, "invalid")))

    companion object {
        const val ME = "u1"
        const val ALICE = "u2"
        const val BOB = "u3"
    }
}

private const val PASSWORD_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
