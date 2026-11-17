package fr.podcast.server.api

import fr.podcast.server.ServerServices
import fr.podcast.server.auth.Action
import fr.podcast.server.auth.BEARER_AUTH
import fr.podcast.server.auth.Role
import fr.podcast.server.auth.SESSION_AUTH
import fr.podcast.server.auth.UserAccount
import fr.podcast.server.auth.can
import fr.podcast.server.catalog.ShareRecord
import fr.podcast.server.catalog.UserCatalog
import fr.podcast.server.hal.episodeResource
import fr.podcast.server.hal.shareResource
import fr.podcast.server.hal.sharedWithMe
import fr.podcast.server.hal.subscriptionResource
import fr.podcast.server.hal.subscriptionShares
import fr.podcast.server.http.FieldError
import fr.podcast.server.http.isHtmx
import fr.podcast.server.http.representation
import fr.podcast.server.http.respondProblem
import fr.podcast.server.web.language
import fr.podcast.shared.model.SharePermission
import fr.podcast.shared.model.ShareRules
import fr.podcast.shared.model.ShareType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post


/**
 * Shares (ADR 0009): a subscription shared READ or WRITE, an episode recommended (READ), and what
 * others share with the user. Every route checks rights with `can`: 404 when the user cannot see the
 * resource, 403 when they can see it but not do this.
 */
fun Route.shareRoutes(services: ServerServices) {
    val sharing = services.sharing

    /** A share the owner, the grantee or an administrator may see; null after a 404. */
    suspend fun ApplicationCall.visibleShare(user: UserAccount): ShareRecord? {
        val record = sharing.record(parameters["id"]!!)
        if (record == null || (user.id != record.ownerId && user.id != record.granteeId && user.role != Role.ADMIN)) {
            respondNotFound("Share")
            return null
        }
        return record
    }

    /** The share answered: htmx reloads what shows shares, and is told [done]; other clients get the share. */
    suspend fun ApplicationCall.respondShare(record: ShareRecord, user: UserAccount, done: String? = null) {
        if (isHtmx) {
            if (done != null) return respondNotice(done, "shares-changed")
            response.header("HX-Trigger", "shares-changed")
            return respond(HttpStatusCode.NoContent)
        }
        val representation = representation()
        respondResource(shareResource(record, user, representation.forms), representation)
    }

    authenticate(SESSION_AUTH, BEARER_AUTH) {
        get("/shared") {
            val representation = call.representation()
            call.respondResource(sharedWithMe(sharing.received(call.user), call.user, representation.forms, services.audioLinks), representation)
        }

        get("/subscriptions/{id}/shares") {
            val user = call.user
            val view = sharing.subscription(user, call.parameters["id"]!!) ?: return@get call.respondNotFound("Subscription")
            if (!user.can(Action.SHARE, view.ownerId, view.permission)) {
                return@get call.respondProblem(HttpStatusCode.Forbidden, "Only its owner sees whom a subscription is shared with")
            }
            val records = sharing.sharesOf(ShareType.SUBSCRIPTION, view.item.subscription.id.toLong())
            val representation = call.representation()
            call.respondResource(subscriptionShares(view.item, records, user, representation.forms), representation)
        }

        post("/subscriptions/{id}/shares") {
            val user = call.mutatingUser() ?: return@post
            val view = sharing.subscription(user, call.parameters["id"]!!) ?: return@post call.respondNotFound("Subscription")
            if (!user.can(Action.SHARE, view.ownerId, view.permission)) {
                return@post call.respondProblem(HttpStatusCode.Forbidden, "Only its owner can share a subscription")
            }
            val request = call.receiveShareRequest(services, view.ownerId, permissionAllowed = true) ?: return@post
            val record = sharing.share(
                ShareType.SUBSCRIPTION, view.item.subscription.id.toLong(), view.ownerId, request.granteeId, request.permission, request.message,
            )
            call.respondShare(record, user, done = call.language().strings.sharedWithUser(record.share.grantee.displayName))
        }

        // A recommendation: any episode of the catalog, to anyone, READ only.
        post("/episodes/{id}/shares") {
            val user = call.mutatingUser() ?: return@post
            val episode = UserCatalog(services.database, user.id).episode(call.parameters["id"]!!) ?: return@post call.respondNotFound("Episode")
            val request = call.receiveShareRequest(services, user.id, permissionAllowed = false) ?: return@post
            val record = sharing.share(ShareType.EPISODE, episode.id.toLong(), user.id, request.granteeId, SharePermission.READ, request.message)
            call.respondShare(record, user, done = call.language().strings.recommendedTo(record.share.grantee.displayName))
        }

        get("/shares/{id}") {
            val user = call.user
            val record = call.visibleShare(user) ?: return@get
            val representation = call.representation()
            val resource = when (record.share.type) {
                ShareType.SUBSCRIPTION -> sharing.subscription(user, record.resourceId.toString())?.let { subscriptionResource(it.item, representation.forms) }
                ShareType.EPISODE -> UserCatalog(services.database, user.id).episodeItem(record.resourceId.toString())
                    ?.let { episodeResource(it, representation.forms, services.audioLinks) }
                ShareType.PLAYLIST -> null
            }
            call.respondResource(shareResource(record, user, representation.forms, resource), representation)
        }

        patch("/shares/{id}") {
            val user = call.mutatingUser() ?: return@patch
            val record = call.visibleShare(user) ?: return@patch
            if (!user.can(Action.SHARE, record.ownerId, null)) {
                return@patch call.respondProblem(HttpStatusCode.Forbidden, "Only the owner changes what a share allows")
            }
            val permission = call.receiveFields()?.stringField("permission")?.let { value -> SharePermission.entries.firstOrNull { it.name == value } }
            val error = when {
                record.share.type == ShareType.EPISODE -> "a recommendation stays READ"
                permission == null -> "READ or WRITE"
                else -> null
            }
            if (error != null) return@patch call.respondProblem(HttpStatusCode.UnprocessableEntity, "Invalid share", listOf(FieldError("permission", error)))
            sharing.setPermission(record.share.id.toLong(), permission!!)
            call.respondShare(sharing.record(record.share.id)!!, user)
        }

        // The owner stops sharing, or the grantee declines.
        delete("/shares/{id}") {
            val user = call.mutatingUser() ?: return@delete
            val record = call.visibleShare(user) ?: return@delete
            sharing.delete(record.share.id.toLong())
            call.respondChanged("shares-changed")
        }
    }
}

private data class ShareRequest(val granteeId: Long, val permission: SharePermission, val message: String?)

/** Whom to share with, how and with what message; null after a 422. */
private suspend fun ApplicationCall.receiveShareRequest(services: ServerServices, ownerId: Long, permissionAllowed: Boolean): ShareRequest? {
    val fields = receiveFields() ?: emptyMap()
    val grantee = (fields.stringField("userId") ?: fields.intField("userId")?.toString())?.toLongOrNull()?.let { services.accounts.user(it) }
    val permissionValue = fields.stringField("permission")?.takeIf { it.isNotBlank() }
    val permission = permissionValue?.let { value -> SharePermission.entries.firstOrNull { it.name == value } } ?: SharePermission.READ
    val message = fields.stringField("message")?.trim()?.ifEmpty { null }
    val errors = buildList {
        when {
            grantee == null -> add(FieldError("userId", "an existing account expected"))
            grantee.id == ownerId -> add(FieldError("userId", "the owner already has it"))
        }
        if (permissionValue != null && (!permissionAllowed || SharePermission.entries.none { it.name == permissionValue })) {
            add(FieldError("permission", if (permissionAllowed) "READ or WRITE" else "a recommendation is READ"))
        }
        if ((message?.length ?: 0) > ShareRules.MAX_MESSAGE_LENGTH) add(FieldError("message", "at most ${ShareRules.MAX_MESSAGE_LENGTH} characters"))
    }
    if (errors.isNotEmpty()) {
        respondProblem(HttpStatusCode.UnprocessableEntity, "Invalid share", errors)
        return null
    }
    return ShareRequest(grantee!!.id, permission, message)
}
