package fr.podcast.server.html

import fr.podcast.server.auth.Device
import fr.podcast.server.auth.Role
import fr.podcast.server.auth.UserAccount
import fr.podcast.shared.i18n.Language
import fr.podcast.shared.i18n.Strings
import fr.podcast.shared.model.AccountRules
import fr.podcast.shared.model.UserRole
import fr.podcast.shared.navigation.Destination
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.html.ButtonType
import kotlinx.html.FORM
import kotlinx.html.FlowContent
import kotlinx.html.HTML
import kotlinx.html.InputType
import kotlinx.html.button
import kotlinx.html.code
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h2
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.stream.createHTML
import kotlinx.html.summary
import kotlinx.html.title
import kotlinx.html.ul
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Empties a form once the server accepted it; a refusal keeps what was typed. */
internal const val RESET_WHEN_ACCEPTED = "if (event.detail.successful) this.reset()"

/**
 * Settings (GET /me in HTML): the profile, the password, the signed-in devices and, for an
 * administrator, the server's accounts ([users]) (ADR 0009). The forms send what the HAL-FORMS
 * templates describe; the answer, or the refusal, appears next to them.
 */
fun HTML.settingsPage(user: UserAccount, devices: List<Device>, users: List<UserAccount>?, language: Language) {
    val strings = language.strings
    page(language, strings.destination(Destination.Settings), user, Destination.Settings, content = {
        section("settings") {
            h2 { +strings.profile }
            p("meta") { +"${user.username} · ${strings.role(user.role.toUserRole())}" }
            form(classes = "fields") {
                attributes["hx-patch"] = "/me"
                attributes["hx-target"] = "#profile-result"
                label {
                    +strings.displayName
                    input(type = InputType.text, name = "displayName") {
                        value = user.displayName
                        required = true
                        maxLength = AccountRules.MAX_DISPLAY_NAME_LENGTH.toString()
                    }
                }
                button(type = ButtonType.submit) { +strings.save }
            }
            result("profile-result")
        }
        section("settings") {
            h2 { +strings.changePassword }
            form(classes = "fields") {
                attributes["hx-put"] = "/me/password"
                attributes["hx-target"] = "#password-result"
                attributes["hx-on::after-request"] = RESET_WHEN_ACCEPTED
                passwordInput(strings.currentPassword, "currentPassword", autocomplete = "current-password")
                passwordInput(strings.newPassword, "newPassword", autocomplete = "new-password", hint = strings.passwordHint(AccountRules.MIN_PASSWORD_LENGTH))
                button(type = ButtonType.submit) { +strings.changePassword }
            }
            result("password-result")
        }
        section("settings") {
            h2 { +strings.devices }
            deviceList(devices, strings)
            h2 { +strings.appPasswords }
            p("meta") { +strings.appPasswordsHint }
            form(classes = "fields") {
                attributes["hx-post"] = "/me/devices"
                attributes["hx-target"] = "#app-password-result"
                attributes["hx-on::after-request"] = RESET_WHEN_ACCEPTED
                label {
                    +strings.appPasswordName
                    input(type = InputType.text, name = "name") {
                        required = true
                        maxLength = "100"
                        placeholder = "AntennaPod"
                    }
                }
                button(type = ButtonType.submit) { +strings.newAppPassword }
            }
            result("app-password-result")
        }
        users?.let { usersSection(user, it, strings) }
    })
}

/** The apps and terminals signed in to the account (a browser session is not one); reloaded when one is signed out. */
private fun FlowContent.deviceList(devices: List<Device>, strings: Strings) {
    ul("rows") {
        id = "devices"
        reloadOn("/me", "devices-changed")
        if (devices.isEmpty()) li("empty") { +strings.noDevices }
        devices.forEach { device ->
            li("row") {
                div("card-body") {
                    span("title") { +device.name }
                    span("meta") {
                        +(device.lastUsedAt?.let { strings.lastUsed(it.toLocalDateTime(TimeZone.currentSystemDefault()).date) } ?: strings.neverUsed)
                    }
                }
                button(classes = "action") {
                    attributes["hx-delete"] = "/me/devices/${device.id}"
                    attributes["hx-confirm"] = strings.revokeDeviceConfirm(device.name)
                    attributes["hx-swap"] = "none"
                    +strings.revokeDevice
                }
            }
        }
    }
}

/** The server's accounts, for an administrator: their own is managed above, the others here. */
private fun FlowContent.usersSection(admin: UserAccount, users: List<UserAccount>, strings: Strings) {
    section("settings") {
        h2 { +strings.users }
        result("users-result")
        ul("rows") {
            id = "users"
            reloadOn("/me", "users-changed")
            users.forEach { account ->
                val self = account.id == admin.id
                li("row") {
                    div("card-body") {
                        span("title") { +(if (self) "${account.displayName} (${strings.you})" else account.displayName) }
                        span("meta") { +"${account.username} · ${strings.role(account.role.toUserRole())}" }
                        if (!self) userActions(account, strings)
                    }
                }
            }
        }
        details("add") {
            summary("action") { +strings.addUser }
            form(classes = "fields") {
                attributes["hx-post"] = "/users"
                attributes["hx-target"] = "#users-result"
                attributes["hx-on::after-request"] = RESET_WHEN_ACCEPTED
                label {
                    +strings.username
                    input(type = InputType.text, name = "username") {
                        required = true
                        pattern = "[a-z0-9][a-z0-9._\\-]{2,31}"
                        title = strings.usernameUnavailable
                        attributes["autocapitalize"] = "none"
                        attributes["autocomplete"] = "off"
                    }
                }
                label {
                    +strings.displayName
                    input(type = InputType.text, name = "displayName") {
                        required = true
                        maxLength = AccountRules.MAX_DISPLAY_NAME_LENGTH.toString()
                    }
                }
                passwordInput(strings.password, "password", autocomplete = "new-password", hint = strings.passwordHint(AccountRules.MIN_PASSWORD_LENGTH))
                label("toggle") {
                    input(type = InputType.checkBox, name = "role") { value = Role.ADMIN.name }
                    +strings.role(UserRole.ADMIN)
                }
                button(type = ButtonType.submit) { +strings.create }
            }
        }
    }
}

/** Make or unmake administrator, reset the password, delete: the `update-user`, `reset-password` and `delete-user` templates. */
private fun FlowContent.userActions(account: UserAccount, strings: Strings) {
    div("actions") {
        val admin = account.role == Role.ADMIN
        button(classes = "action") {
            attributes["hx-patch"] = "/users/${account.id}"
            attributes["hx-vals"] = buildJsonObject { put("role", if (admin) Role.USER.name else Role.ADMIN.name) }.toString()
            attributes["hx-target"] = "#users-result"
            +(if (admin) strings.removeAdmin else strings.makeAdmin)
        }
        details {
            summary("action") { +strings.resetPassword }
            form(classes = "fields") {
                attributes["hx-put"] = "/users/${account.id}/password"
                attributes["hx-target"] = "#users-result"
                attributes["hx-on::after-request"] = RESET_WHEN_ACCEPTED
                passwordInput(strings.resetPasswordOf(account.displayName), "password", autocomplete = "new-password", hint = strings.resetPasswordNotice)
                button(type = ButtonType.submit) { +strings.resetPassword }
            }
        }
        button(classes = "action danger") {
            attributes["hx-delete"] = "/users/${account.id}"
            attributes["hx-confirm"] = strings.deleteUserConfirm(account.displayName)
            attributes["hx-target"] = "#users-result"
            +strings.deleteUser
        }
    }
}

private fun FORM.passwordInput(label: String, name: String, autocomplete: String, hint: String? = null) {
    label {
        +label
        input(type = InputType.password, name = name) {
            required = true
            attributes["autocomplete"] = autocomplete
            if (name != "currentPassword") minLength = AccountRules.MIN_PASSWORD_LENGTH.toString()
        }
        hint?.let { span("hint") { +it } }
    }
}

/** Where the server's answer to a form appears. */
internal fun FlowContent.result(id: String) {
    p("result") {
        this.id = id
        attributes["aria-live"] = "polite"
    }
}

/** The element (it needs an id) reloads itself from [page] when the server announces one of [events] (HX-Trigger). */
internal fun kotlinx.html.HTMLTag.reloadOn(page: String, vararg events: String) {
    val elementId = attributes["id"] ?: error("reloadOn needs an id")
    attributes["hx-get"] = page
    attributes["hx-select"] = "#$elementId"
    attributes["hx-trigger"] = events.joinToString(", ") { "$it from:body" }
    attributes["hx-swap"] = "outerHTML"
    // htmx would pass hx-select and hx-swap on to every form, button and boosted link inside: their
    // answer, a sentence or another page, has no such element and would be swapped for nothing.
    attributes["hx-disinherit"] = "*"
}

private fun Role.toUserRole() = UserRole.valueOf(name)

/** The new app password, shown this once; the devices list reloads with it (ADR 0011). */
fun appPasswordFragment(name: String, password: String, strings: Strings): String = createHTML(prettyPrint = false).span("notice") {
    +strings.appPasswordCreated(name)
    +" "
    code("secret") { +password }
    +" "
    +strings.appPasswordShownOnce
}
