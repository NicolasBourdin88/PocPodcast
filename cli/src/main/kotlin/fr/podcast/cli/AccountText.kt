package fr.podcast.cli

import fr.podcast.shared.i18n.Strings
import fr.podcast.shared.model.Access
import fr.podcast.shared.model.Device
import fr.podcast.shared.model.Share
import fr.podcast.shared.model.SharePermission
import fr.podcast.shared.model.User
import fr.podcast.shared.repository.SharedItem
import kotlinx.datetime.TimeZone

/** `podcast profile`: the user as the active server knows them. */
fun renderProfileText(me: User, strings: Strings): String = "${me.displayName} — ${me.username} · ${strings.role(me.role)}"

/** Numbered devices for `podcast devices`; `devices revoke <n>` uses the numbers. */
fun renderDevicesText(devices: List<Device>, strings: Strings, timeZone: TimeZone = TimeZone.currentSystemDefault()): String = buildString {
    if (devices.isEmpty()) return strings.noDevices
    devices.forEachIndexed { index, device ->
        val detail = if (device.current) strings.thisDevice else device.lastUsedOn(timeZone)?.let(strings::lastUsed) ?: strings.neverUsed
        appendLine("${number(index)} ${device.name} — $detail")
    }
}.trimEnd()

/** Numbered accounts for `podcast users`; the `users` subcommands use the numbers. */
fun renderUsersText(users: List<User>, me: User, strings: Strings): String = buildString {
    users.forEachIndexed { index, user ->
        val self = if (user.id == me.id) " (${strings.you})" else ""
        appendLine("${number(index)} ${user.displayName}$self — ${user.username} · ${strings.role(user.role)}")
    }
}.trimEnd()

/** Numbered for `podcast shared`; `shared remove <n>` uses the numbers. */
fun renderSharedText(items: List<SharedItem>, strings: Strings): String = buildString {
    if (items.isEmpty()) return strings.nothingShared
    items.forEachIndexed { index, item ->
        val owner = item.share.owner.displayName
        item.subscription?.let {
            val permission = if (it.subscription.access == Access.WRITE) SharePermission.WRITE else SharePermission.READ
            appendLine("${number(index)} ${it.podcast.title} — ${strings.sharedBy(owner)} · ${strings.permission(permission)}")
        }
        item.episode?.let { appendLine("${number(index)} ${it.episode.title} — ${it.podcast.title} · ${strings.recommendedBy(owner)}") }
        item.playlist?.let {
            val permission = if (it.access == Access.WRITE) SharePermission.WRITE else SharePermission.READ
            appendLine("${number(index)} ${it.title} — ${strings.playlistSharedBy(owner)} · ${strings.permission(permission)} · ${strings.playlistKind(it.kind)}")
        }
        item.share.message?.let { appendLine("    ✉ $it") }
    }
}.trimEnd()

/** Whom a subscription or a playlist is shared with, for `podcast share <n>` and `podcast playlists share <n>`. */
fun renderSharesText(shares: List<Share>, strings: Strings, none: String = strings.notShared): String = buildString {
    if (shares.isEmpty()) return none
    shares.forEach { share ->
        appendLine("  ⇄ ${share.grantee.displayName} — ${strings.permission(share.permission)}")
        share.message?.let { appendLine("    ✉ $it") }
    }
}.trimEnd()
