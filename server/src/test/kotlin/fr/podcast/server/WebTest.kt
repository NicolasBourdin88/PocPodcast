package fr.podcast.server

import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun theThinClientPlaysEpisodesAndShowsTheQueue() = testApplication {
        podcastServer()
        val browser = browser()
        browser.login()
        suspend fun page(path: String) = browser.get(path) {
            header(HttpHeaders.Accept, "text/html")
            header(HttpHeaders.AcceptLanguage, "fr")
        }.bodyAsText()

        // The player opens on the episode to resume (LCC 330), with a signed link; every episode can be played.
        val home = page("/")
        assertContains(home, "id=\"player\"")
        assertContains(home, "data-episode=\"2\"")
        assertContains(home, "data-audio=\"/episodes/2/audio?expires=")
        assertContains(home, "data-play=\"1\"")
        assertContains(home, "src=\"/static/player.js\"")
        assertContains(home, "La file d'attente est vide.")

        // htmx sends form fields, with HX-Request; "Play next" puts the episode first.
        val added = browser.submitForm("/queue", parameters { append("episodeId", "1") }) { header("HX-Request", "true") }
        assertEquals(HttpStatusCode.NoContent, added.status)
        assertEquals("queue-changed, episodes-changed", added.headers["HX-Trigger"])
        browser.submitForm("/queue", parameters { append("episodeId", "3"); append("position", "0") }) { header("HX-Request", "true") }
        val queue = page("/fragments/queue")
        assertTrue(queue.indexOf("KMP in production") < queue.indexOf("Compose Multiplatform for iOS is stable"))
        assertContains(queue, "hx-delete=\"/queue/1\"")
        assertContains(queue, "Monter dans la file")

        // Without HX-Request a session cannot change the queue (CSRF, ADR 0005).
        assertEquals(HttpStatusCode.Forbidden, browser.submitForm("/queue", parameters { append("episodeId", "4") }).status)

        val script = browser.get("/static/player.js")
        assertEquals(HttpStatusCode.OK, script.status)
        assertContains(script.bodyAsText(), "playNext")
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

    @Test
    fun libraryAndExplorePagesWorkWithHtmx() = testApplication {
        podcastServer()
        val browser = browser()
        browser.login()
        fun HttpRequestBuilder.frenchHtmx() {
            header("HX-Request", "true")
            header(HttpHeaders.AcceptLanguage, "fr")
        }

        val library = browser.get("/subscriptions") {
            header(HttpHeaders.Accept, "text/html")
            header(HttpHeaders.AcceptLanguage, "fr")
        }.bodyAsText()
        assertContains(library, "Bibliothèque")
        assertContains(library, "hx-patch=\"/subscriptions/1\"")
        assertContains(library, "href=\"/subscriptions/opml\"")

        val explore = browser.get("/search?q=kotlin") {
            header(HttpHeaders.Accept, "text/html")
            header(HttpHeaders.AcceptLanguage, "fr")
        }.bodyAsText()
        assertContains(explore, "Le Podcast de Test")
        assertContains(explore, "Abonné") // Alice already follows Talking Kotlin

        // htmx gets fragments, even for refusals (htmx only swaps successful responses).
        val subscribed = browser.submitForm("/subscriptions", parameters { append("feedUrl", "https://feeds.test/podcast20.xml") }) { frenchHtmx() }
        assertEquals(HttpStatusCode.OK, subscribed.status)
        assertEquals("subscriptions-changed", subscribed.headers["HX-Trigger"])
        assertContains(subscribed.bodyAsText(), "Abonné à « Le Podcast de Test »")
        val refused = browser.submitForm("/subscriptions", parameters { append("feedUrl", "https://feeds.test/not-a-feed.html") }) { frenchHtmx() }
        assertContains(refused.bodyAsText(), "Cette adresse n'est pas un flux de podcast.")

        val noted = browser.submitForm("/subscriptions/1", parameters { append("note", "Le vendredi") }, encodeInQuery = false) {
            method = HttpMethod.Patch
            frenchHtmx()
        }
        assertEquals(HttpStatusCode.OK, noted.status)
        assertContains(browser.get("/subscriptions") { header(HttpHeaders.Accept, "text/html") }.bodyAsText(), "Le vendredi")

        val report = browser.submitFormWithBinaryData(
            "/subscriptions/opml",
            formData { append("opml", javaClass.getResourceAsStream("/feeds/import.opml")!!.readBytes(), Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"a.opml\"") }) },
        ) { frenchHtmx() }
        assertContains(report.bodyAsText(), "importé(s)")
    }
}
