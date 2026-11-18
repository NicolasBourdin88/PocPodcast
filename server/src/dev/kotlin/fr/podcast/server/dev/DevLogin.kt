package fr.podcast.server.dev

import fr.podcast.server.auth.Accounts
import fr.podcast.server.auth.UserSession
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import java.net.InetAddress

/** The demo accounts that [devLogin] signs in. */
private val demoAccounts = setOf(DevSeed.ALICE, DevSeed.BOB)

/**
 * Development only: `/dev/login/alice` signs this machine's browser in as a demo account, without
 * its password, so that the thin client can be checked (by hand or by a script) in one step;
 * `?next=/playlists` chooses the page to land on. Another machine, or another account, gets 404.
 * The production distribution does not contain it.
 */
fun Application.devLogin(accounts: Accounts) {
    routing {
        get("/dev/login/{username}") {
            val username = call.parameters["username"]!!
            val user = accounts.userByUsername(username)
                ?.takeIf { username in demoAccounts && isLoopback(call.request.local.remoteAddress) }
                ?: return@get call.respond(HttpStatusCode.NotFound)
            call.sessions.set(UserSession(user.id, user.credentialsVersion))
            // Only a page of this server: never another site.
            val next = call.request.queryParameters["next"]?.takeIf { it.startsWith("/") && !it.startsWith("//") && !it.startsWith("/\\") }
            call.respondRedirect(next ?: "/")
        }
    }
}

/** Whether the connection comes from this machine (the socket's peer, not a forwarded header). */
fun isLoopback(address: String): Boolean = runCatching { InetAddress.getByName(address).isLoopbackAddress }.getOrDefault(false)
