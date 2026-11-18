package fr.podcast.server.api

import fr.podcast.server.ServerServices
import fr.podcast.server.auth.BEARER_AUTH
import fr.podcast.server.auth.Role
import fr.podcast.server.auth.SESSION_AUTH
import fr.podcast.server.auth.SessionPrincipal
import fr.podcast.server.auth.TokenPrincipal
import fr.podcast.server.auth.UserAccount
import fr.podcast.server.auth.UserSession
import fr.podcast.server.auth.authPrincipal
import fr.podcast.server.hal.appPasswordResource
import fr.podcast.server.hal.deviceCollection
import fr.podcast.server.hal.meResource
import fr.podcast.server.hal.userCollection
import fr.podcast.server.hal.userResource
import fr.podcast.server.html.appPasswordFragment
import fr.podcast.server.html.settingsPage
import fr.podcast.server.http.FieldError
import fr.podcast.server.http.ProblemTypes
import fr.podcast.server.http.Representation
import fr.podcast.server.http.isHtmx
import fr.podcast.server.http.representation
import fr.podcast.server.http.respondProblem
import fr.podcast.server.web.language
import fr.podcast.shared.model.AccountRules
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.html.respondHtml
import io.ktor.server.plugins.origin
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import fr.podcast.server.http.HalJson
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set


/**
 * Accounts (ADR 0009): the directory of users, their management by administrators, the user's own
 * profile and devices. In HTML, /me is the thin client's Settings page.
 */
fun Route.accountRoutes(services: ServerServices) {
    val accounts = services.accounts

    authenticate(SESSION_AUTH, BEARER_AUTH) {
        get("/me") {
            val user = call.user
            val representation = call.representation()
            if (representation == Representation.Html) {
                val devices = accounts.devices(user.id, (call.authPrincipal as? TokenPrincipal)?.token)
                val users = if (user.role == Role.ADMIN) accounts.users() else null
                return@get call.respondHtml { settingsPage(user, devices, users, call.language()) }
            }
            call.respondResource(meResource(user, representation.forms), representation)
        }

        patch("/me") {
            val user = call.mutatingUser() ?: return@patch
            val displayName = call.receiveFields()?.stringField("displayName")?.trim()
            if (displayName.isNullOrEmpty() || displayName.length > AccountRules.MAX_DISPLAY_NAME_LENGTH) {
                return@patch call.respondProblem(
                    HttpStatusCode.UnprocessableEntity, "Invalid profile",
                    listOf(FieldError("displayName", "between 1 and ${AccountRules.MAX_DISPLAY_NAME_LENGTH} characters")),
                )
            }
            accounts.rename(user.id, displayName)
            if (call.isHtmx) return@patch call.respondNotice(call.language().strings.profileSaved)
            val representation = call.representation()
            call.respondResource(meResource(accounts.user(user.id)!!, representation.forms), representation)
        }

        put("/me/password") {
            val user = call.mutatingUser() ?: return@put
            val fields = call.receiveFields() ?: emptyMap()
            val current = fields.stringField("currentPassword").orEmpty()
            val new = fields.stringField("newPassword").orEmpty()
            val address = call.request.origin.remoteAddress
            services.loginThrottle.retryAfterSeconds(user.username, address)?.let { return@put call.respondTooManyAttempts(it) }
            if (accounts.authenticate(user.username, current) == null) {
                services.loginThrottle.failed(user.username, address)
                return@put call.respondProblem(
                    HttpStatusCode.UnprocessableEntity, "Wrong password",
                    listOf(FieldError("currentPassword", "wrong password")),
                )
            }
            services.loginThrottle.succeeded(user.username, address)
            passwordError(new, "newPassword")?.let { return@put call.respondProblem(HttpStatusCode.UnprocessableEntity, "Invalid password", listOf(it)) }
            // Every other device signs in again; this one stays signed in.
            val principal = call.authPrincipal
            val version = accounts.setPassword(user.id, new, keepToken = (principal as? TokenPrincipal)?.token)
            if (principal is SessionPrincipal) call.sessions.set(UserSession(user.id, version))
            if (call.isHtmx) return@put call.respondNotice(call.language().strings.passwordChanged, "devices-changed")
            call.respond(HttpStatusCode.NoContent)
        }

        get("/me/devices") {
            val token = (call.authPrincipal as? TokenPrincipal)?.token
            val representation = call.representation()
            call.respondResource(deviceCollection(accounts.devices(call.user.id, token), representation.forms), representation)
        }

        post("/me/devices") {
            val user = call.mutatingUser() ?: return@post
            val name = call.receiveFields()?.stringField("name")?.trim()
            if (name.isNullOrEmpty() || name.length > MAX_DEVICE_NAME) {
                return@post call.respondProblem(
                    HttpStatusCode.UnprocessableEntity, "Invalid app password", listOf(FieldError("name", "1 to $MAX_DEVICE_NAME characters")),
                )
            }
            val token = accounts.issueToken(user, name)
            if (call.isHtmx) {
                call.response.header("HX-Trigger", "devices-changed")
                return@post call.respondFragment(appPasswordFragment(token.deviceName, token.value, call.language().strings))
            }
            call.respondText(appPasswordResource(token).toString(), HalJson, HttpStatusCode.Created)
        }

        delete("/me/devices/{id}") {
            val user = call.mutatingUser() ?: return@delete
            val deviceId = call.parameters["id"]!!.toLongOrNull()
            if (deviceId == null || !accounts.revokeDevice(user.id, deviceId)) return@delete call.respondNotFound("Device")
            call.respondChanged("devices-changed")
        }

        get("/users") {
            val representation = call.representation()
            call.respondResource(userCollection(accounts.users(), call.user, representation.forms), representation)
        }

        get("/users/{id}") {
            val user = call.parameters["id"]!!.toLongOrNull()?.let { accounts.user(it) } ?: return@get call.respondNotFound("User")
            val representation = call.representation()
            call.respondResource(userResource(user, call.user, representation.forms), representation)
        }

        post("/users") {
            val admin = call.mutatingAdmin() ?: return@post
            val fields = call.receiveFields() ?: emptyMap()
            val username = fields.stringField("username")?.trim()?.lowercase().orEmpty()
            val displayName = fields.stringField("displayName")?.trim().orEmpty()
            val password = fields.stringField("password").orEmpty()
            val role = fields.stringField("role")?.takeIf { it.isNotBlank() } ?: Role.USER.name
            val errors = buildList {
                if (!AccountRules.validUsername(username)) add(FieldError("username", "3 to 32 characters: lowercase letters, digits, '.', '_' or '-'"))
                else if (accounts.userByUsername(username) != null) add(FieldError("username", "already taken"))
                if (displayName.isEmpty() || displayName.length > AccountRules.MAX_DISPLAY_NAME_LENGTH) add(FieldError("displayName", "between 1 and ${AccountRules.MAX_DISPLAY_NAME_LENGTH} characters"))
                passwordError(password, "password")?.let(::add)
                if (Role.entries.none { it.name == role }) add(FieldError("role", "USER or ADMIN"))
            }
            if (errors.isNotEmpty()) return@post call.respondProblem(HttpStatusCode.UnprocessableEntity, "Invalid account", errors)
            val created = accounts.create(username, displayName, password, Role.valueOf(role))
            if (call.isHtmx) return@post call.respondNotice(call.language().strings.userCreated(created.displayName), "users-changed")
            call.response.header(HttpHeaders.Location, "/users/${created.id}")
            val representation = call.representation()
            call.respondResource(userResource(created, admin, representation.forms), representation, HttpStatusCode.Created)
        }

        patch("/users/{id}") {
            val admin = call.mutatingAdmin() ?: return@patch
            val user = call.parameters["id"]!!.toLongOrNull()?.let { accounts.user(it) } ?: return@patch call.respondNotFound("User")
            val fields = call.receiveFields() ?: emptyMap()
            val displayName = fields.stringField("displayName")?.trim()
            val role = fields.stringField("role")
            val errors = buildList {
                if (displayName != null && (displayName.isEmpty() || displayName.length > AccountRules.MAX_DISPLAY_NAME_LENGTH)) {
                    add(FieldError("displayName", "between 1 and ${AccountRules.MAX_DISPLAY_NAME_LENGTH} characters"))
                }
                if (role != null && Role.entries.none { it.name == role }) add(FieldError("role", "USER or ADMIN"))
            }
            if (errors.isNotEmpty()) return@patch call.respondProblem(HttpStatusCode.UnprocessableEntity, "Invalid account", errors)
            if (role != null && !accounts.setRole(user.id, Role.valueOf(role))) {
                return@patch call.respondProblem(
                    HttpStatusCode.UnprocessableEntity, "The server needs an administrator",
                    listOf(FieldError("role", "the last administrator stays one")),
                )
            }
            displayName?.let { accounts.rename(user.id, it) }
            if (call.isHtmx) return@patch call.respondChanged("users-changed")
            val representation = call.representation()
            call.respondResource(userResource(accounts.user(user.id)!!, admin, representation.forms), representation)
        }

        put("/users/{id}/password") {
            call.mutatingAdmin() ?: return@put
            val user = call.parameters["id"]!!.toLongOrNull()?.let { accounts.user(it) } ?: return@put call.respondNotFound("User")
            val password = call.receiveFields()?.stringField("password").orEmpty()
            passwordError(password, "password")?.let { return@put call.respondProblem(HttpStatusCode.UnprocessableEntity, "Invalid password", listOf(it)) }
            // A reset by an administrator signs the account out everywhere.
            accounts.setPassword(user.id, password, keepToken = null)
            if (call.isHtmx) return@put call.respondNotice(call.language().strings.passwordReset(user.displayName))
            call.respond(HttpStatusCode.NoContent)
        }

        delete("/users/{id}") {
            val admin = call.mutatingAdmin() ?: return@delete
            val user = call.parameters["id"]!!.toLongOrNull()?.let { accounts.user(it) } ?: return@delete call.respondNotFound("User")
            if (user.id == admin.id) return@delete call.respondProblem(HttpStatusCode.Forbidden, "An administrator cannot delete their own account")
            val podcastIds = services.database.query { catalogQueries.podcastIdsOfUser(user.id).executeAsList() }
            accounts.delete(user.id)
            // The podcasts nobody follows any more go too, with their files.
            for (podcastId in podcastIds) {
                if (services.database.query { catalogQueries.countSubscribers(podcastId).executeAsOne() } == 0L) services.removePodcast(podcastId)
            }
            call.respondChanged("users-changed")
        }
    }
}

/** The administrator making a change; anyone else gets 403 (they may read accounts, not manage them). */
private suspend fun ApplicationCall.mutatingAdmin(): UserAccount? {
    val user = mutatingUser() ?: return null
    if (user.role != Role.ADMIN) {
        respondProblem(HttpStatusCode.Forbidden, "Only an administrator can manage accounts")
        return null
    }
    return user
}

private fun passwordError(password: String, field: String): FieldError? =
    if (password.length < AccountRules.MIN_PASSWORD_LENGTH) FieldError(field, "at least ${AccountRules.MIN_PASSWORD_LENGTH} characters") else null

/** 429 with Retry-After: too many wrong passwords from this address (ADR 0009). */
/** As the device name of a token (POST /auth/tokens). */
private const val MAX_DEVICE_NAME = 100

internal suspend fun ApplicationCall.respondTooManyAttempts(retryAfterSeconds: Long) {
    response.header(HttpHeaders.RetryAfter, retryAfterSeconds.toString())
    respondProblem(
        HttpStatusCode.TooManyRequests, "Too many failed sign-ins: try again in $retryAfterSeconds s",
        type = ProblemTypes.TOO_MANY_ATTEMPTS,
    )
}
