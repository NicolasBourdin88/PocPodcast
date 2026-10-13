package poc.auth

import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.basic
import io.ktor.server.auth.bearer
import io.ktor.server.auth.principal
import io.ktor.server.auth.session
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.sessions.SessionStorageMemory
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import kotlinx.serialization.Serializable

/** Thin client session; the cookie only carries an id, the data stays on the server. */
@Serializable
data class UserSession(val userId: Long)

data class Playlist(val id: Long, val ownerId: Long, var title: String) {
    val resource get() = Resource(ResourceType.PLAYLIST, id, ownerId)
}

class World(val accounts: Accounts = Accounts(), val sharing: Sharing = Sharing()) {
    val playlists = mutableMapOf<Long, Playlist>()
}

fun Application.pocModule(world: World) {
    val accounts = world.accounts

    install(Sessions) {
        cookie<UserSession>("podcast_session", SessionStorageMemory()) {
            cookie.httpOnly = true
            cookie.secure = false // true in production (HTTPS)
            cookie.extensions["SameSite"] = "Lax" // blocks cross-site form posts (CSRF) for the thin client
        }
    }
    install(Authentication) {
        session<UserSession>("session") {
            validate { session -> accounts.user(session.userId) }
            challenge { call.respond(HttpStatusCode.Unauthorized) }
        }
        bearer("token") {
            authenticate { credential -> accounts.userForToken(credential.token) }
        }
        basic("gpodder") {
            realm = "gPodder"
            validate { credential -> accounts.checkBasic(credential.name, credential.password) }
        }
    }

    routing {
        // Thin client: HTML form login, session cookie.
        post("/login") {
            val form: Parameters = call.receiveParameters()
            val user = accounts.checkPassword(form["name"].orEmpty(), form["password"].orEmpty())
                ?: return@post call.respond(HttpStatusCode.Unauthorized)
            call.sessions.set(UserSession(user.id))
            call.respond(HttpStatusCode.NoContent)
        }

        // Rich clients (one token per server and device): exchange the password for a token once.
        authenticate("gpodder") {
            post("/api/tokens") {
                val user = call.principal<User>()!!
                call.respondText(accounts.issueToken(user, device = call.receiveText().ifBlank { "unknown" }))
            }
        }

        // gPodder compatibility: Basic login sets the "sessionid" cookie expected by gPodder clients.
        authenticate("gpodder") {
            post("/api/2/auth/{username}/login.json") {
                val user = call.principal<User>()!!
                if (user.name != call.parameters["username"]) return@post call.respond(HttpStatusCode.BadRequest)
                call.response.cookies.append(Cookie("sessionid", accounts.issueToken(user, "gpodder"), httpOnly = true))
                call.respond(HttpStatusCode.OK)
            }
        }

        // The same resources whatever the authentication method.
        authenticate("session", "token") {
            get("/api/playlists/{id}") {
                val (user, playlist) = call.userAndPlaylist(world) ?: return@get
                if (!world.sharing.can(user, Action.READ, playlist.resource)) return@get call.respond(HttpStatusCode.NotFound)
                call.respondText(playlist.title)
            }
            put("/api/playlists/{id}") {
                val (user, playlist) = call.userAndPlaylist(world) ?: return@put
                when {
                    !world.sharing.can(user, Action.READ, playlist.resource) -> call.respond(HttpStatusCode.NotFound)
                    !world.sharing.can(user, Action.MODIFY, playlist.resource) -> call.respond(HttpStatusCode.Forbidden)
                    else -> {
                        playlist.title = call.receiveText()
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }
            delete("/api/playlists/{id}") {
                val (user, playlist) = call.userAndPlaylist(world) ?: return@delete
                when {
                    !world.sharing.can(user, Action.READ, playlist.resource) -> call.respond(HttpStatusCode.NotFound)
                    !world.sharing.can(user, Action.DELETE, playlist.resource) -> call.respond(HttpStatusCode.Forbidden)
                    else -> {
                        world.playlists.remove(playlist.id)
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }
        }
    }
}

private suspend fun ApplicationCall.userAndPlaylist(world: World): Pair<User, Playlist>? {
    val user = principal<User>()!!
    val playlist = world.playlists[parameters["id"]?.toLongOrNull()]
    if (playlist == null) {
        respond(HttpStatusCode.NotFound)
        return null
    }
    return user to playlist
}
