package fr.podcast.server.auth

import fr.podcast.server.db.ServerDatabase
import fr.podcast.server.http.respondUnauthenticated
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.auth.principal
import io.ktor.server.auth.session
import io.ktor.server.sessions.SessionStorage
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

const val SESSION_AUTH = "session"
const val BEARER_AUTH = "bearer"
const val SESSION_COOKIE = "podcast_session"

/** The cookie gPodder clients expect after `login.json` (ADR 0011). */
const val GPODDER_SESSION_COOKIE = "sessionid"

/**
 * Thin client session; the cookie only carries an id, the data stays in the database.
 * [version] is the account's credentials version: a new password ends the older sessions.
 */
@Serializable
data class UserSession(val userId: Long, val version: Int = 0)

/**
 * Session of a gPodder client, opened by `login.json` (ADR 0011). Like [UserSession] it ends with
 * a new password, and with the app password it was opened with ([tokenId]), if any.
 */
@Serializable
data class GpodderSession(val userId: Long, val version: Int, val tokenId: Long? = null)

/** Who made the request, and how. */
sealed interface AuthPrincipal {
    val user: UserAccount
}

data class SessionPrincipal(override val user: UserAccount) : AuthPrincipal

data class TokenPrincipal(override val user: UserAccount, val token: String) : AuthPrincipal

val ApplicationCall.authPrincipal: AuthPrincipal? get() = principal<AuthPrincipal>()

class DatabaseSessionStorage(private val database: ServerDatabase, private val lifetime: Duration) : SessionStorage {
    override suspend fun write(id: String, value: String) {
        val expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(lifetime.inWholeSeconds)
        database.query { sessionsQueries.writeSession(id, value, expiresAt) }
    }

    override suspend fun invalidate(id: String) {
        database.query { sessionsQueries.deleteSession(id) }
    }

    override suspend fun read(id: String): String =
        database.query { sessionsQueries.readSession(id).executeAsOneOrNull() }
            ?: throw NoSuchElementException("Session $id not found")
}

/**
 * Session cookie for the thin client, Bearer tokens for every other client.
 * Routes always accept both ([SESSION_AUTH] first, so its challenge answers with a Problem).
 */
fun Application.installAuthentication(accounts: Accounts, database: ServerDatabase, secureCookies: Boolean) {
    val sessionLifetime = 30.days
    install(Sessions) {
        cookie<UserSession>(SESSION_COOKIE, DatabaseSessionStorage(database, sessionLifetime)) {
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.secure = secureCookies
            cookie.maxAgeInSeconds = sessionLifetime.inWholeSeconds
            cookie.extensions["SameSite"] = "Lax"
        }
        cookie<GpodderSession>(GPODDER_SESSION_COOKIE, DatabaseSessionStorage(database, sessionLifetime)) {
            cookie.path = "/api/2"
            cookie.httpOnly = true
            cookie.secure = secureCookies
            cookie.maxAgeInSeconds = sessionLifetime.inWholeSeconds
            // gPodder clients are not browsers: no other site may send it.
            cookie.extensions["SameSite"] = "Strict"
        }
    }
    install(Authentication) {
        session<UserSession>(SESSION_AUTH) {
            validate { session ->
                accounts.user(session.userId)?.takeIf { it.credentialsVersion == session.version }?.let(::SessionPrincipal)
            }
            challenge { call.respondUnauthenticated() }
        }
        bearer(BEARER_AUTH) {
            realm = "podcast"
            authenticate { credential -> accounts.userForToken(credential.token)?.let { TokenPrincipal(it, credential.token) } }
        }
    }
}
