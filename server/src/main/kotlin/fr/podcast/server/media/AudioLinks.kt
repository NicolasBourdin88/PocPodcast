package fr.podcast.server.media

import fr.podcast.server.db.ServerDatabase
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Signed, expiring addresses of the audio files (ADR 0008). Native players (Media3, AVPlayer,
 * JavaFX) and `<audio>` cannot send the Bearer token: the link itself carries the right to read
 * the file, for [validity]. Expiry is rounded to the hour, so that a link stays the same for a
 * while instead of changing with every response.
 */
class AudioLinks(
    private val key: ByteArray,
    private val validity: Duration = 24.hours,
    private val clock: Clock = Clock.System,
) {
    sealed interface Check {
        data object Valid : Check
        data object Expired : Check
        data object Invalid : Check
    }

    fun path(episodeId: String): String {
        val expires = (clock.now() + validity).epochSeconds.let { (it + HOUR - 1) / HOUR * HOUR }
        return "/episodes/$episodeId/audio?expires=$expires&signature=${sign(episodeId, expires)}"
    }

    fun check(episodeId: String, expires: String?, signature: String?): Check {
        val expiresAt = expires?.toLongOrNull() ?: return Check.Invalid
        if (signature == null) return Check.Invalid
        // Constant time: no hint about how much of the signature is right.
        val expected = sign(episodeId, expiresAt).toByteArray()
        if (!MessageDigest.isEqual(expected, signature.toByteArray())) return Check.Invalid
        return if (clock.now().epochSeconds > expiresAt) Check.Expired else Check.Valid
    }

    private fun sign(episodeId: String, expires: Long): String {
        val mac = Mac.getInstance(ALGORITHM).apply { init(SecretKeySpec(key, ALGORITHM)) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal("$episodeId:$expires".encodeToByteArray()))
    }

    companion object {
        private const val ALGORITHM = "HmacSHA256"
        private const val HOUR = 3600L
        private const val SECRET_NAME = "audio-links"

        /** The server's key, generated on the very first start and kept in the database. */
        suspend fun load(database: ServerDatabase): AudioLinks {
            val key = database.transaction {
                secretsQueries.secret(SECRET_NAME).executeAsOneOrNull() ?: run {
                    secretsQueries.insertSecret(SECRET_NAME, ByteArray(32).also(SecureRandom()::nextBytes))
                    secretsQueries.secret(SECRET_NAME).executeAsOne()
                }
            }
            return AudioLinks(key)
        }
    }
}
