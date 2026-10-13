package fr.podcast.server.html

import fr.podcast.shared.i18n.Language
import kotlinx.html.ButtonType
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.InputType
import kotlinx.html.button
import kotlinx.html.form
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.p

fun HTML.loginPage(language: Language, username: String = "", error: String? = null) {
    val strings = language.strings
    page(language, strings.signInTitle, user = null, content = {
        form(action = "/login", method = FormMethod.post, classes = "login") {
            label {
                +strings.username
                input(type = InputType.text, name = "username") {
                    value = username
                    required = true
                    autoFocus = username.isEmpty()
                    attributes["autocomplete"] = "username"
                }
            }
            label {
                +strings.password
                input(type = InputType.password, name = "password") {
                    required = true
                    autoFocus = username.isNotEmpty()
                    attributes["autocomplete"] = "current-password"
                }
            }
            if (error != null) p("error") { +error }
            button(type = ButtonType.submit) { +strings.signIn }
        }
    })
}
