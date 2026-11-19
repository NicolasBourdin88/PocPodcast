package fr.podcast.server.html

import fr.podcast.server.auth.UserAccount
import fr.podcast.shared.i18n.Strings
import fr.podcast.shared.model.Avatar
import kotlinx.html.FlowContent
import kotlinx.html.FlowOrPhrasingContent
import kotlinx.html.InputType
import kotlinx.html.fieldSet
import kotlinx.html.form
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.legend
import kotlinx.html.span

/** The user's picture (ADR 0013): their avatar, or else the one taken from their username. */
val UserAccount.picture: Avatar get() = Avatar.of(avatar, username)

/** An avatar: its emoji on its color, read out as the person's name ([label]). */
fun FlowOrPhrasingContent.avatar(avatar: Avatar, label: String? = null, size: String = "") {
    span("avatar $size".trim()) {
        attributes["style"] = "background:#%06X".format(avatar.color)
        if (label != null) attributes["aria-label"] = label else attributes["aria-hidden"] = "true"
        attributes["role"] = "img".takeIf { label != null } ?: "presentation"
        +avatar.emoji
    }
}

/**
 * The avatars to choose from, as on streaming services: a click saves the choice at once (the
 * `update-profile` template, its `avatar` options).
 */
fun FlowContent.avatarPicker(user: UserAccount, strings: Strings) {
    form(classes = "avatars") {
        attributes["hx-patch"] = "/me"
        attributes["hx-trigger"] = "change"
        attributes["hx-target"] = "#profile-result"
        fieldSet {
            legend { +strings.chooseAvatar }
            Avatar.entries.forEach { choice ->
                label {
                    input(type = InputType.radio, name = "avatar") {
                        value = choice.id
                        checked = choice == user.picture
                    }
                    avatar(choice, strings.avatarName(choice), size = "large")
                }
            }
        }
    }
}
