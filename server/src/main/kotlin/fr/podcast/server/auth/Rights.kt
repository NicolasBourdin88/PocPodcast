package fr.podcast.server.auth

import fr.podcast.shared.model.Access
import fr.podcast.shared.model.SharePermission

/** What a user wants to do with a resource that belongs to someone (ADR 0005). */
enum class Action { READ, MODIFY, DELETE, SHARE }

/**
 * The single rule every route goes through (ADR 0005): the owner and administrators may do
 * everything; a WRITE share reads and modifies; a READ share only reads; anyone else nothing,
 * and gets 404 rather than 403, so as not to reveal what exists.
 */
fun UserAccount.can(action: Action, ownerId: Long, permission: SharePermission?): Boolean = when {
    id == ownerId || role == Role.ADMIN -> true
    permission == SharePermission.WRITE -> action == Action.READ || action == Action.MODIFY
    permission == SharePermission.READ -> action == Action.READ
    else -> false
}

/** What [can] allows, as clients see it; null when the resource is not readable at all. */
fun UserAccount.access(ownerId: Long, permission: SharePermission?): Access? = when {
    can(Action.SHARE, ownerId, permission) -> Access.OWNER
    can(Action.MODIFY, ownerId, permission) -> Access.WRITE
    can(Action.READ, ownerId, permission) -> Access.READ
    else -> null
}
