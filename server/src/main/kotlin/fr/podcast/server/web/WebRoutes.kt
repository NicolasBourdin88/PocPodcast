package fr.podcast.server.web

import fr.podcast.server.auth.Accounts
import fr.podcast.server.auth.BEARER_AUTH
import fr.podcast.server.auth.SESSION_AUTH
import fr.podcast.server.auth.UserSession
import fr.podcast.server.auth.authPrincipal
import fr.podcast.server.catalog.UserCatalog
import fr.podcast.server.db.ServerDatabase
import fr.podcast.server.html.homeFragmentHtml
import fr.podcast.server.html.loginPage
import fr.podcast.server.http.respondSeeOther
import fr.podcast.shared.home.LoadHomeState
import fr.podcast.shared.i18n.Language
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.html.respondHtml
import io.ktor.server.request.acceptLanguageItems
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set

/** Interface language negotiated from Accept-Language (JSON stays language-neutral). */
fun ApplicationCall.language(): Language = Language.negotiate(request.acceptLanguageItems().map { it.value })

/** Thin client pages that only exist in HTML: sign-in, sign-out, htmx fragments. */
fun Route.webRoutes(database: ServerDatabase, accounts: Accounts) {
    get("/login") {
        call.respondHtml { loginPage(call.language()) }
    }

    post("/login") {
        val form = call.receiveParameters()
        val username = form["username"].orEmpty()
        val user = accounts.authenticate(username, form["password"].orEmpty())
        if (user == null) {
            val language = call.language()
            return@post call.respondHtml(HttpStatusCode.Unauthorized) { loginPage(language, username, language.strings.invalidCredentials) }
        }
        call.sessions.set(UserSession(user.id))
        call.respondSeeOther("/")
    }

    post("/logout") {
        call.sessions.clear<UserSession>()
        call.respondSeeOther("/login")
    }

    authenticate(SESSION_AUTH, BEARER_AUTH) {
        get("/fragments/home") {
            val user = call.authPrincipal!!.user
            val home = LoadHomeState(UserCatalog(database, user.id))()
            call.respondText(homeFragmentHtml(home, call.language().strings), ContentType.Text.Html)
        }
    }
}
