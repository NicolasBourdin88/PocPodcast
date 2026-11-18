package fr.podcast.server.api

import fr.podcast.server.ServerServices
import fr.podcast.server.auth.GpodderSession
import fr.podcast.server.auth.UserAccount
import fr.podcast.server.http.respondProblem
import fr.podcast.server.sync.EpisodeAction
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.basicAuthenticationCredentials
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** The JSON of the gPodder and Open Podcast APIs: absent rather than null. */
internal val syncJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    // Always written, even empty: AntennaPod reads update_urls unconditionally.
    encodeDefaults = true
}

private val DEVICE_ID = Regex("[A-Za-z0-9._-]{1,64}")
private val DEVICE_TYPES = setOf("desktop", "laptop", "mobile", "server", "other")

/**
 * The gPodder API v2, in the subset AntennaPod uses (ADR 0011). Its clients sign in with Basic on
 * `login.json`, then send the `sessionid` cookie; Basic, or a Bearer token, works on any call.
 * The answers are the API's JSON, not hypermedia: existing clients decide their format.
 */
fun Route.gpodderRoutes(services: ServerServices) {
    val sync = services.gpodder

    route("/api/2") {
        post("/auth/{username}/login.json") {
            val username = call.parameters["username"]!!
            val current = call.sessions.get<GpodderSession>()?.let { session -> services.sessionUser(session)?.let { session to it } }
            if (call.request.header(HttpHeaders.Authorization) == null && current != null && current.second.username == username) {
                return@post call.respond(HttpStatusCode.OK)
            }
            val (user, tokenId) = call.basicSignIn(services, username) ?: return@post
            // A client signing in again at each synchronisation keeps its session.
            if (current == null || current.second.id != user.id || current.first.tokenId != tokenId) {
                call.sessions.set(GpodderSession(user.id, user.credentialsVersion, tokenId))
            }
            call.respond(HttpStatusCode.OK)
        }

        post("/auth/{username}/logout.json") {
            call.sessions.clear<GpodderSession>()
            call.respond(HttpStatusCode.OK)
        }

        get("/devices/{username}.json") {
            val user = call.syncUser(services) ?: return@get
            call.respondSyncJson(syncJson.encodeToString(sync.devices(user)))
        }

        post("/devices/{username}/{device}.json") {
            val user = call.syncUser(services) ?: return@post
            val device = call.deviceId() ?: return@post
            val body = call.receiveText().takeIf { it.isNotBlank() }?.let { text ->
                runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
                    ?: return@post call.respondProblem(HttpStatusCode.BadRequest, "A JSON object expected")
            }
            val caption = (body?.get("caption") as? JsonPrimitive)?.contentOrNull?.take(100)
            val type = (body?.get("type") as? JsonPrimitive)?.contentOrNull?.lowercase()?.let { if (it in DEVICE_TYPES) it else "other" }
            sync.saveDevice(user, device, caption, type)
            call.respond(HttpStatusCode.OK)
        }

        get("/subscriptions/{username}/{device}.json") {
            val user = call.syncUser(services) ?: return@get
            call.deviceId() ?: return@get
            val since = call.since() ?: return@get
            call.respondSyncJson(syncJson.encodeToString(sync.subscriptionChanges(user, since)))
        }

        post("/subscriptions/{username}/{device}.json") {
            val user = call.syncUser(services) ?: return@post
            val device = call.deviceId() ?: return@post
            val changes = runCatching { Json.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
            val add = changes?.urls("add")
            val remove = changes?.urls("remove")
            if (add == null || remove == null) return@post call.respondProblem(HttpStatusCode.BadRequest, "Lists \"add\" and \"remove\" of feed URLs expected")
            if (add.any { it in remove }) return@post call.respondProblem(HttpStatusCode.BadRequest, "The same feed cannot be added and removed at once")
            // An unknown device appears in the list, as with gpodder.net.
            if (sync.devices(user).none { it.id == device }) sync.saveDevice(user, device, null, null)
            call.respondSyncJson(syncJson.encodeToString(sync.uploadSubscriptionChanges(user, add, remove)))
        }

        get("/episodes/{username}.json") {
            val user = call.syncUser(services) ?: return@get
            val since = call.since() ?: return@get
            val podcast = call.request.queryParameters["podcast"]?.takeIf { it.isNotBlank() }
            call.respondSyncJson(syncJson.encodeToString(sync.episodeActions(user, since, podcast)))
        }

        post("/episodes/{username}.json") {
            val user = call.syncUser(services) ?: return@post
            val actions = runCatching { syncJson.decodeFromString<List<EpisodeAction>>(call.receiveText()) }.getOrNull()
                ?: return@post call.respondProblem(HttpStatusCode.BadRequest, "A list of episode actions expected")
            call.respondSyncJson(syncJson.encodeToString(sync.uploadEpisodeActions(user, actions)))
        }
    }
}

/**
 * The account of a gPodder or Open Podcast API call (ADR 0011): its gPodder session, a Bearer
 * token, or Basic with the password or an app password. When the path names a user
 * ([username]), it must be that one. Null after answering 401 or 429.
 */
internal suspend fun ApplicationCall.syncUser(services: ServerServices, username: String? = parameters["username"]): UserAccount? {
    sessions.get<GpodderSession>()?.let { session ->
        val user = services.sessionUser(session)
        if (user == null) sessions.clear<GpodderSession>() else if (username == null || user.username == username) return user
    }
    val authorization = request.header(HttpHeaders.Authorization)
    if (authorization != null && authorization.startsWith("Bearer ", ignoreCase = true)) {
        val user = services.accounts.userForToken(authorization.substringAfter(' ').trim())
        if (user != null && (username == null || user.username == username)) return user
        respondSyncUnauthorized()
        return null
    }
    return basicSignIn(services, username)?.first
}

/** Basic credentials, limited like every sign-in (ADR 0009); with the app password's id, if one was used. */
private suspend fun ApplicationCall.basicSignIn(services: ServerServices, username: String?): Pair<UserAccount, Long?>? {
    val credentials = request.basicAuthenticationCredentials(Charsets.UTF_8)
    if (credentials == null || (username != null && credentials.name != username)) {
        respondSyncUnauthorized()
        return null
    }
    val address = request.origin.remoteAddress
    services.loginThrottle.retryAfterSeconds(credentials.name, address)?.let {
        respondTooManyAttempts(it)
        return null
    }
    val signedIn = services.accounts.authenticateWithSecret(credentials.name, credentials.password)
    if (signedIn == null) {
        services.loginThrottle.failed(credentials.name, address)
        respondSyncUnauthorized()
        return null
    }
    services.loginThrottle.succeeded(credentials.name, address)
    return signedIn
}

/** A session still valid: same credentials, and its app password not revoked. */
private suspend fun ServerServices.sessionUser(session: GpodderSession): UserAccount? =
    accounts.user(session.userId)?.takeIf { user ->
        user.credentialsVersion == session.version && session.tokenId?.let { accounts.tokenIsActive(it) } != false
    }

/** gPodder clients answer a Basic challenge (some only send credentials after one). */
private suspend fun ApplicationCall.respondSyncUnauthorized() {
    response.header(HttpHeaders.WWWAuthenticate, "Basic realm=\"Podcast\", charset=\"UTF-8\"")
    respondProblem(HttpStatusCode.Unauthorized, "Wrong username or password")
}

internal suspend fun ApplicationCall.respondSyncJson(json: String, status: HttpStatusCode = HttpStatusCode.OK) =
    respondText(json, ContentType.Application.Json, status)

private suspend fun ApplicationCall.deviceId(): String? =
    parameters["device"]?.takeIf { DEVICE_ID.matches(it) } ?: run {
        respondProblem(HttpStatusCode.BadRequest, "A device id is made of letters, digits, '.', '_' or '-'")
        null
    }

/** `since`, in seconds: absent means from the start. */
private suspend fun ApplicationCall.since(): Long? {
    val value = request.queryParameters["since"] ?: return 0
    return value.toLongOrNull()?.takeIf { it >= 0 } ?: run {
        respondProblem(HttpStatusCode.BadRequest, "\"since\" is a timestamp in seconds")
        null
    }
}

private fun JsonObject.urls(name: String): List<String>? = when (val value = get(name)) {
    null -> emptyList()
    is JsonArray -> value.map { (it as? JsonPrimitive)?.contentOrNull ?: return null }
    else -> null
}
