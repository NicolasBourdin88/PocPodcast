package fr.podcast.server

import fr.podcast.server.dev.devLogin
import fr.podcast.server.dev.isLoopback
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The development server's sign-in link (source set `dev`, outside the distribution). */
class DevLoginTest {

    @BeforeTest
    fun reset() = TestDatabase.reset()

    @Test
    fun aDemoAccountSignsInWithoutItsPassword() = testApplication {
        val services = podcastServer()
        application { devLogin(services.accounts) }
        val browser = createClient {
            install(HttpCookies)
            followRedirects = false
        }

        val signedIn = browser.get("/dev/login/alice?next=/playlists")
        assertEquals(HttpStatusCode.Found, signedIn.status)
        assertEquals("/playlists", signedIn.headers[HttpHeaders.Location])
        val page = browser.get("/playlists") {
            header(HttpHeaders.Accept, "text/html")
            header(HttpHeaders.AcceptLanguage, "fr")
        }
        assertEquals(HttpStatusCode.OK, page.status)
        assertContains(page.bodyAsText(), "Nouvelle playlist")

        // Never towards another site.
        assertEquals("/", browser.get("/dev/login/bob?next=//example.com").headers[HttpHeaders.Location])
        // Only the demo accounts.
        assertEquals(HttpStatusCode.NotFound, browser.get("/dev/login/carol").status)
    }

    @Test
    fun onlyFromThisMachine() {
        assertTrue(isLoopback("127.0.0.1"))
        assertTrue(isLoopback("0:0:0:0:0:0:0:1"))
        assertFalse(isLoopback("192.168.1.20"))
        assertFalse(isLoopback("10.0.2.2"))
    }
}
