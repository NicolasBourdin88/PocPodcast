package fr.podcast.server.web

import fr.podcast.server.auth.Accounts
import fr.podcast.server.auth.LoginThrottle
import fr.podcast.server.auth.BEARER_AUTH
import fr.podcast.server.auth.SESSION_AUTH
import fr.podcast.server.auth.UserSession
import fr.podcast.server.auth.authPrincipal
import fr.podcast.server.catalog.UserCatalog
import fr.podcast.server.db.ServerDatabase
import fr.podcast.server.html.homeFragmentHtml
import fr.podcast.server.html.queueFragmentHtml
import fr.podcast.server.media.AudioLinks
import fr.podcast.server.html.loginPage
import fr.podcast.server.http.respondSeeOther
import fr.podcast.shared.home.LoadHomeState
import fr.podcast.shared.i18n.Language
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.html.respondHtml
import io.ktor.server.http.content.staticResources
import io.ktor.server.request.acceptLanguageItems
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import fr.podcast.server.ServerServices
import fr.podcast.server.api.homeExtras

/** Interface language negotiated from Accept-Language (JSON stays language-neutral). */
fun ApplicationCall.language(): Language = Language.negotiate(request.acceptLanguageItems().map { it.value })

/** Thin client pages that only exist in HTML: sign-in, sign-out, htmx fragments, the player script. */
fun Route.webRoutes(services: ServerServices) {
    val database = services.database
    val accounts = services.accounts
    val audio = services.audioLinks
    val throttle = services.loginThrottle
    staticResources("/static", "static")

    get("/login") {
        call.respondHtml { loginPage(call.language()) }
    }

    post("/login") {
        val form = call.receiveParameters()
        val username = form["username"].orEmpty()
        val language = call.language()
        val address = call.request.origin.remoteAddress
        throttle.retryAfterSeconds(username, address)?.let { seconds ->
            call.response.header(HttpHeaders.RetryAfter, seconds.toString())
            val minutes = ((seconds + 59) / 60).toInt()
            return@post call.respondHtml(HttpStatusCode.TooManyRequests) { loginPage(language, username, language.strings.tooManyAttempts(minutes)) }
        }
        val user = accounts.authenticate(username, form["password"].orEmpty())
        if (user == null) {
            throttle.failed(username, address)
            return@post call.respondHtml(HttpStatusCode.Unauthorized) { loginPage(language, username, language.strings.invalidCredentials) }
        }
        throttle.succeeded(username, address)
        call.sessions.set(UserSession(user.id, user.credentialsVersion))
        call.respondSeeOther("/")
    }

    post("/logout") {
        call.sessions.clear<UserSession>()
        call.respondSeeOther("/login")
    }

    authenticate(SESSION_AUTH, BEARER_AUTH) {
        get("/fragments/home") {
            val user = call.authPrincipal!!.user
            val catalog = UserCatalog(database, user.id)
            val home = LoadHomeState(catalog)()
            call.respondText(homeFragmentHtml(home, catalog.queue(), services.homeExtras(user), call.language().strings, audio), ContentType.Text.Html)
        }

        get("/fragments/queue") {
            val queue = UserCatalog(database, call.authPrincipal!!.user.id).queue()
            call.respondText(queueFragmentHtml(queue, call.language().strings, audio), ContentType.Text.Html)
        }
    }
}
