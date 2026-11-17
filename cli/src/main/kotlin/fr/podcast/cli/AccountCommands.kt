package fr.podcast.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.prompt
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import fr.podcast.shared.model.User
import fr.podcast.shared.model.UserRole
import kotlinx.coroutines.runBlocking

/** `podcast profile [--name …]`: the account as the server knows it (ADR 0009). */
class ProfileCommand : ServerCommand("profile") {
    private val name by option("--name", help = systemLanguage.strings.displayName)

    override fun help(context: Context) = systemLanguage.strings.cliProfileDescription
    override fun run() {
        val newName = name
        val me = if (newName == null) {
            call { client().me() }
        } else {
            call { client().updateProfile(newName.trim()) }.also { updated ->
                // The account saved on this computer shows the new name too.
                if (!cli.demo) cli.accounts.state.value.active?.let { cli.accounts.update(it.copy(displayName = updated.displayName)) }
                echo(cli.strings.profileSaved)
            }
        }
        echo(renderProfileText(me, cli.strings))
    }
}

/** `podcast passwd`: this computer stays signed in, the other devices sign in again. */
class PasswdCommand : ServerCommand("passwd") {
    private val current by option("--current").prompt(systemLanguage.strings.currentPassword, hideInput = true)
    private val new by option("--new").newPassword(systemLanguage.strings.newPassword)

    override fun help(context: Context) = systemLanguage.strings.cliPasswdDescription
    override fun run() {
        call { client().changePassword(current, new) }
        echo(cli.strings.passwordChanged)
    }
}

/** `podcast devices`, and `devices revoke <n>`. */
class DevicesCommand : ServerCommand("devices") {
    override val invokeWithoutSubcommand = true

    override fun help(context: Context) = systemLanguage.strings.cliDevicesDescription
    override fun run() {
        if (currentContext.invokedSubcommand == null) echo(renderDevicesText(call { client().devices() }, cli.strings))
    }
}

class DevicesRevokeCommand : ServerCommand("revoke") {
    private val number by argument(help = "1, 2… (podcast devices)").int()

    override fun help(context: Context) = systemLanguage.strings.cliDevicesRevokeDescription
    override fun run() {
        val device = call { client().devices() }.getOrNull(number - 1) ?: fail(cli.strings.unknownNumber(number))
        val active = cli.accounts.state.value.active
        if (device.current && !cli.demo && active != null) {
            // This computer: the same as `podcast logout`.
            runBlocking { cli.accountService.signOut(active) }
            cli.accounts.remove(active.id)
            return echo(cli.strings.signedOut)
        }
        call { client().revokeDevice(device.id) }
        echo(cli.strings.deviceSignedOut(device.name))
    }
}

/** `podcast users`: the server's accounts; administrators manage them with the subcommands. */
class UsersCommand : ServerCommand("users") {
    override val invokeWithoutSubcommand = true

    override fun help(context: Context) = systemLanguage.strings.cliUsersDescription
    override fun run() {
        if (currentContext.invokedSubcommand != null) return
        val me = call { client().me() }
        echo(renderUsersText(call { client().users() }, me, cli.strings))
    }
}

class UsersAddCommand : ServerCommand("add") {
    private val username by argument(help = "carol")
    private val name by option("--name", help = systemLanguage.strings.displayName)
    private val admin by option("--admin", help = systemLanguage.strings.role(UserRole.ADMIN)).flag()
    private val password by option("--password").newPassword(systemLanguage.strings.password)

    override fun help(context: Context) = systemLanguage.strings.cliUsersAddDescription
    override fun run() {
        val user = call { client().createUser(username.trim().lowercase(), name ?: username, password, if (admin) UserRole.ADMIN else UserRole.USER) }
        echo(cli.strings.userCreated(user.displayName))
    }
}

class UsersRemoveCommand : ServerCommand("remove") {
    private val number by argument(help = "1, 2… (podcast users)").int()
    private val yes by option("--yes").flag()

    override fun help(context: Context) = systemLanguage.strings.cliUsersRemoveDescription
    override fun run() {
        val user = user(number)
        if (!yes) {
            echo(cli.strings.deleteUserConfirm(user.displayName))
            fail(cli.strings.confirmWithYes)
        }
        call { client().deleteUser(user.id) }
        echo(cli.strings.userDeleted(user.displayName))
    }
}

class UsersPasswdCommand : ServerCommand("passwd") {
    private val number by argument(help = "1, 2… (podcast users)").int()
    private val password by option("--password").newPassword(systemLanguage.strings.newPassword)

    override fun help(context: Context) = systemLanguage.strings.cliUsersPasswdDescription
    override fun run() {
        val user = user(number)
        call { client().resetPassword(user.id, password) }
        echo(cli.strings.passwordReset(user.displayName))
    }
}

class UsersRoleCommand : ServerCommand("role") {
    private val number by argument(help = "1, 2… (podcast users)").int()
    private val role by argument(help = "admin | user").choice("admin" to UserRole.ADMIN, "user" to UserRole.USER)

    override fun help(context: Context) = systemLanguage.strings.cliUsersRoleDescription
    override fun run() {
        val updated = call { client().updateUser(user(number).id, role = role) }
        echo("${updated.displayName} — ${updated.username} · ${cli.strings.role(updated.role)}")
    }
}

/** The account shown under [number] by `podcast users`. */
private fun ServerCommand.user(number: Int): User = call { client().users() }.getOrNull(number - 1) ?: fail(cli.strings.unknownNumber(number))

/** A new password, typed twice without being shown (or given as the option, for scripts). */
private fun com.github.ajalt.clikt.parameters.options.NullableOption<String, String>.newPassword(label: String) = prompt(
    label,
    hideInput = true,
    requireConfirmation = true,
    confirmationPrompt = systemLanguage.strings.confirmPassword,
    confirmationMismatchMessage = systemLanguage.strings.passwordsDiffer,
)
