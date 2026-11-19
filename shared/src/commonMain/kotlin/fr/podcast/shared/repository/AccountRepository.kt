package fr.podcast.shared.repository

import fr.podcast.shared.model.Device
import fr.podcast.shared.model.User
import fr.podcast.shared.model.UserRole

/** The signed-in account, its devices, and the accounts of the server (ADR 0009). */
interface AccountRepository {
    /** The signed-in user; an [UserRole.ADMIN] manages the accounts. */
    @Throws(Exception::class)
    suspend fun me(): User
    /** Changes what is given: the name, the [fr.podcast.shared.model.Avatar] id, or both. */
    @Throws(Exception::class)
    suspend fun updateProfile(displayName: String? = null, avatar: String? = null): User

    /** This device stays signed in; the others must sign in again. */
    @Throws(Exception::class)
    suspend fun changePassword(currentPassword: String, newPassword: String)

    /** The devices signed in to the account, this one marked [fr.podcast.shared.model.Device.current]. */
    @Throws(Exception::class)
    suspend fun devices(): List<Device>

    /** Signs a device out (a lost phone…): its token stops working. */
    @Throws(Exception::class)
    suspend fun revokeDevice(deviceId: String)

    /**
     * A password for a gPodder client such as AntennaPod (ADR 0011), returned this once: a device
     * token named [name], listed and revoked with the devices.
     */
    @Throws(Exception::class)
    suspend fun createAppPassword(name: String): String

    /** Every account of the server, by name: whom to share with. */
    @Throws(Exception::class)
    suspend fun users(): List<User>

    // Administrators only: the server does not offer these actions to the others.

    @Throws(Exception::class)
    suspend fun createUser(username: String, displayName: String, password: String, role: UserRole = UserRole.USER): User

    /** Null keeps the current value. The last administrator cannot become a user. */
    @Throws(Exception::class)
    suspend fun updateUser(userId: String, displayName: String? = null, role: UserRole? = null): User

    /** Also signs the account out everywhere. */
    @Throws(Exception::class)
    suspend fun resetPassword(userId: String, password: String)

    /** Not one's own; the podcasts nobody else follows go too. */
    @Throws(Exception::class)
    suspend fun deleteUser(userId: String)
}
