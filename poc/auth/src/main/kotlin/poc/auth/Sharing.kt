package poc.auth

enum class ResourceType { SUBSCRIPTION, PLAYLIST, EPISODE }

enum class Permission { READ, WRITE }

enum class Action { READ, MODIFY, DELETE, SHARE }

data class Resource(val type: ResourceType, val id: Long, val ownerId: Long)

/** A resource shared by its owner with another user. */
data class Share(val type: ResourceType, val resourceId: Long, val granteeId: Long, val permission: Permission)

class Sharing {
    private val shares = mutableListOf<Share>()

    fun share(resource: Resource, grantee: User, permission: Permission) {
        shares.removeAll { it.type == resource.type && it.resourceId == resource.id && it.granteeId == grantee.id }
        shares += Share(resource.type, resource.id, grantee.id, permission)
    }

    fun unshare(resource: Resource, grantee: User) {
        shares.removeAll { it.type == resource.type && it.resourceId == resource.id && it.granteeId == grantee.id }
    }

    /**
     * Single authorization rule used by every endpoint:
     * owner and admin can do everything, WRITE can read and modify, READ can only read.
     * Only the owner can delete or re-share.
     */
    fun can(user: User, action: Action, resource: Resource): Boolean {
        if (user.admin || user.id == resource.ownerId) return true
        val permission = shares.firstOrNull {
            it.type == resource.type && it.resourceId == resource.id && it.granteeId == user.id
        }?.permission ?: return false
        return when (action) {
            Action.READ -> true
            Action.MODIFY -> permission == Permission.WRITE
            Action.DELETE, Action.SHARE -> false
        }
    }
}
