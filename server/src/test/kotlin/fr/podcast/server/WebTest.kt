package fr.podcast.server

import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class WebTest {

    @BeforeTest
    fun reset() = TestDatabase.reset()

    private fun ApplicationTestBuilder.browser() = createClient {
        install(HttpCookies)
        followRedirects = false
    }

    private suspend fun io.ktor.client.HttpClient.login(language: String = "fr") = submitForm(
        "/login",
        parameters { append("username", TestSeed.ALICE); append("password", TestSeed.ALICE_PASSWORD) },
    ) { header(HttpHeaders.AcceptLanguage, language) }

    @Test
    fun browserSignsInThenSeesItsHome() = testApplication {
        podcastServer()
        val browser = browser()

        val anonymous = browser.get("/") { header(HttpHeaders.Accept, "text/html") }
        assertEquals(HttpStatusCode.SeeOther, anonymous.status)
        assertEquals("/login", anonymous.headers[HttpHeaders.Location])

        val wrong = browser.submitForm("/login", parameters { append("username", "alice"); append("password", "nope") }) {
            header(HttpHeaders.AcceptLanguage, "fr")
        }
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        assertContains(wrong.bodyAsText(), "Identifiant ou mot de passe incorrect.")

        assertEquals(HttpStatusCode.SeeOther, browser.login().status)
        val home = browser.get("/") {
            header(HttpHeaders.Accept, "text/html")
            header(HttpHeaders.AcceptLanguage, "fr")
        }.bodyAsText()
        assertContains(home, "Reprendre l'écoute")
        assertContains(home, "LCC 330 - Les nouveautés de Kotlin 2.4")
        assertContains(home, "Connecté en tant que Alice")
        // Episode actions come from the same affordances as the HAL-FORMS templates.
        assertContains(home, "hx-post=\"/episodes/1/played\"")
        assertContains(home, "Marquer comme écouté")
    }

    @Test
    fun htmlFollowsAcceptLanguage() = testApplication {
        podcastServer()
        val browser = browser()
        browser.login()

        val english = browser.get("/") {
            header(HttpHeaders.Accept, "text/html")
            header(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9")
        }.bodyAsText()

        assertContains(english, "Continue listening")
        assertContains(english, "Oct 12")
        assertContains(english, "lang=\"en\"")
    }

    @Test
    fun sessionChangesRequireHtmx() = testApplication {
        podcastServer()
        val browser = browser()
        browser.login()

        // A cross-site form post cannot add HX-Request: refused (CSRF).
        assertEquals(HttpStatusCode.Forbidden, browser.post("/episodes/1/played").status)

        val htmx = browser.post("/episodes/1/played") { header("HX-Request", "true") }
        assertEquals(HttpStatusCode.NoContent, htmx.status)
        assertEquals("episodes-changed", htmx.headers["HX-Trigger"])

        val fragment = browser.get("/fragments/home") {
            header("HX-Request", "true")
            header(HttpHeaders.AcceptLanguage, "fr")
        }.bodyAsText()
        assertContains(fragment.trimStart(), "<div id=\"home\"")
        assertContains(fragment, "hx-delete=\"/episodes/1/played\"")
    }

    @Test
    fun logoutEndsTheSession() = testApplication {
        podcastServer()
        val browser = browser()
        browser.login()

        assertEquals(HttpStatusCode.SeeOther, browser.post("/logout").status)
        assertEquals(HttpStatusCode.SeeOther, browser.get("/") { header(HttpHeaders.Accept, "text/html") }.status)
    }
}
