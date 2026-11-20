package fr.podcast.server

import fr.podcast.shared.model.Avatar
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
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
        assertContains(queue, ">Monter<")

        // Without HX-Request a session cannot change the queue (CSRF, ADR 0005).
        assertEquals(HttpStatusCode.Forbidden, browser.submitForm("/queue", parameters { append("episodeId", "4") }).status)

        // Navigation boosted by htmx (Accept: */*) gets pages, with the player to preserve.
        val boosted = browser.get("/subscriptions") {
            header("HX-Request", "true")
            header("HX-Boosted", "true")
            header(HttpHeaders.Accept, "*/*")
        }
        assertContains(boosted.headers[HttpHeaders.ContentType].orEmpty(), "text/html")
        assertContains(boosted.bodyAsText(), "hx-preserve=\"true\"")

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

        // Artwork comes from this server, never from the feed's host (ADR 0013).
        TestDatabase.execute("UPDATE podcasts SET artwork_url = 'https://cdn.test/artwork.png' WHERE id = 1")
        val withArtwork = browser.get("/subscriptions") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertContains(withArtwork, "src=\"/podcasts/1/artwork\"")
        assertTrue("cdn.test" !in withArtwork)

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

    /** What htmx sends: form fields and HX-Request, from a French browser. */
    private suspend fun io.ktor.client.HttpClient.htmx(method: HttpMethod, path: String, vararg fields: Pair<String, String>) = request(path) {
        this.method = method
        header("HX-Request", "true")
        header(HttpHeaders.AcceptLanguage, "fr")
        if (fields.isNotEmpty()) setBody(FormDataContent(parameters { fields.forEach { (name, value) -> append(name, value) } }))
    }

    private suspend fun io.ktor.client.HttpClient.page(path: String) = get(path) {
        header(HttpHeaders.Accept, "text/html")
        header(HttpHeaders.AcceptLanguage, "fr")
    }.bodyAsText()

    @Test
    fun settingsAreTheHtmlOfMe() = testApplication {
        podcastServer()
        val alice = browser()
        alice.login()

        val settings = alice.page("/me")
        assertContains(settings, "href=\"/me\" aria-current=\"page\"")
        assertContains(settings, "hx-patch=\"/me\"")
        assertContains(settings, "hx-put=\"/me/password\"")
        assertContains(settings, "Appareils connectés")
        // Alice administers the server: the accounts, each with its actions, hers without.
        assertContains(settings, "hx-delete=\"/users/2\"")
        assertContains(settings, "Alice (vous)")
        assertTrue("hx-delete=\"/users/1\"" !in settings)

        val saved = alice.htmx(HttpMethod.Patch, "/me", "displayName" to "Alicia")
        assertEquals(HttpStatusCode.OK, saved.status)
        assertContains(saved.bodyAsText(), "Profil enregistré.")

        // A refusal keeps its status; htmx shows the sentence the apps would show.
        val wrong = alice.htmx(HttpMethod.Put, "/me/password", "currentPassword" to "wrong-password", "newPassword" to "alice-new-password")
        assertEquals(HttpStatusCode.UnprocessableEntity, wrong.status)
        assertContains(wrong.bodyAsText(), "Le mot de passe actuel est incorrect.")

        val created = alice.htmx(HttpMethod.Post, "/users", "username" to "carol", "displayName" to "Carol", "password" to "carol-password")
        assertEquals(HttpStatusCode.OK, created.status)
        assertContains(created.bodyAsText(), "Compte de Carol créé.")
        assertEquals("users-changed", created.headers["HX-Trigger"])
        val taken = alice.htmx(HttpMethod.Post, "/users", "username" to "carol", "displayName" to "Carol", "password" to "carol-password")
        assertContains(taken.bodyAsText(), "Identifiant invalide ou déjà pris")
        val lastAdmin = alice.htmx(HttpMethod.Patch, "/users/1", "role" to "USER")
        assertContains(lastAdmin.bodyAsText(), "Le serveur doit garder au moins un administrateur.")

        // Bob only sees his own account.
        val bob = browser()
        bob.submitForm("/login", parameters { append("username", TestSeed.BOB); append("password", TestSeed.BOB_PASSWORD) })
        val bobSettings = bob.page("/me")
        assertContains(bobSettings, "hx-put=\"/me/password\"")
        assertTrue("hx-post=\"/users\"" !in bobSettings)
        assertEquals(HttpStatusCode.Forbidden, bob.htmx(HttpMethod.Delete, "/users/1").status)
    }

    @Test
    fun theLibraryShowsSharesBothWays() = testApplication {
        podcastServer()
        val alice = browser()
        alice.login()
        val bob = browser()
        bob.submitForm("/login", parameters { append("username", TestSeed.BOB); append("password", TestSeed.BOB_PASSWORD) })

        // Alice shares Les Cast Codeurs (subscription 1) with Bob, who may modify it.
        val shared = alice.htmx(HttpMethod.Post, "/subscriptions/1/shares", "userId" to "2", "permission" to "WRITE", "message" to "Pour toi")
        assertEquals(HttpStatusCode.OK, shared.status)
        assertContains(shared.bodyAsText(), "Partagé avec Bob.")
        assertEquals("shares-changed", shared.headers["HX-Trigger"])
        assertContains(alice.page("/subscriptions"), "Partagé avec")

        val bobsLibrary = bob.page("/subscriptions")
        assertContains(bobsLibrary, "Partagés avec moi")
        assertContains(bobsLibrary, "Partagé par Alice · Lecture et modification")
        assertContains(bobsLibrary, "Pour toi")
        assertContains(bobsLibrary, "hx-patch=\"/subscriptions/1\"") // the note, which he may change

        // Bob recommends an episode back from his Home.
        assertContains(bob.page("/"), "hx-post=\"/episodes/")
        val recommended = bob.htmx(HttpMethod.Post, "/episodes/2/shares", "userId" to "1", "message" to "À partir de 12 min")
        assertContains(recommended.bodyAsText(), "Recommandé à Alice.")
        assertContains(alice.page("/subscriptions"), "Recommandé par Bob")
    }

    @Test
    fun playlistsInTheThinClient() = testApplication {
        podcastServer()
        val alice = browser()
        alice.login()
        assertContains(alice.page("/playlists"), "hx-post=\"/playlists\"")

        // Created from the form: htmx goes to its page, as a boosted link would.
        val rules = arrayOf("keywords" to "", "favoritesOnly" to "false", "unplayedOnly" to "false", "minRating" to "0", "order" to "NEWEST", "limit" to "50")
        val created = alice.htmx(HttpMethod.Post, "/playlists", "title" to "Pour le train", "kind" to "STATIC", *rules)
        assertEquals("/playlists/1", created.headers["HX-Location"])
        val added = alice.htmx(HttpMethod.Post, "/playlists/1/episodes", "episodeId" to "2")
        assertContains(added.bodyAsText(), "Ajouté à « Pour le train ».")
        alice.htmx(HttpMethod.Put, "/playlists/1/feed")

        val page = alice.page("/playlists/1")
        assertContains(page, "LCC 330 - Les nouveautés de Kotlin 2.4")
        assertContains(page, "data-play-all=\"2\"")
        assertContains(page, "http://localhost/feeds/")
        assertContains(page, "hx-post=\"/playlists/1/shares\"")
        assertContains(alice.page("/"), "hx-post=\"/playlists/1/episodes\"") // Home adds to it

        // A dynamic one: a checked box sends "false" then "true", the last value counts.
        alice.htmx(
            HttpMethod.Post, "/playlists", "title" to "Kotlin", "kind" to "DYNAMIC", *rules,
            "keywords" to "kotlin", "unplayedOnly" to "true",
        )
        assertContains(alice.page("/playlists/2"), "kotlin · Non écoutés seulement")
        assertEquals("/playlists", alice.htmx(HttpMethod.Delete, "/playlists/2").headers["HX-Location"])
    }

    @Test
    fun selfReloadingElementsPassNothingOn() = testApplication {
        podcastServer()
        val alice = browser()
        alice.login()
        val rules = arrayOf("keywords" to "", "favoritesOnly" to "false", "unplayedOnly" to "false", "minRating" to "0", "order" to "NEWEST", "limit" to "50")
        alice.htmx(HttpMethod.Post, "/playlists", "title" to "Pour le train", "kind" to "STATIC", *rules)

        // An element that reloads itself on an event: htmx would otherwise pass its hx-swap and
        // hx-select on to the forms and links inside, whose answers would then replace or empty it.
        val reloading = Regex("""<[a-z]+ [^>]*hx-trigger="[^"]*from:body[^"]*"[^>]*>""")
        for (path in listOf("/", "/subscriptions", "/playlists", "/playlists/1", "/me")) {
            val elements = reloading.findAll(alice.page(path)).map { it.value }.toList()
            assertTrue(elements.isNotEmpty(), path)
            elements.forEach { assertContains(it, "hx-disinherit=\"*\"", message = "$path: $it") }
        }
    }

    @Test
    fun anAppPasswordIsShownOnceInSettings() = testApplication {
        podcastServer()
        val alice = browser()
        alice.login()
        assertContains(alice.page("/me"), "hx-post=\"/me/devices\"")

        val created = alice.htmx(HttpMethod.Post, "/me/devices", "name" to "AntennaPod")
        assertEquals("devices-changed", created.headers["HX-Trigger"])
        val answer = created.bodyAsText()
        assertContains(answer, "Mot de passe d'application pour « AntennaPod » :")
        assertTrue(Regex("<code class=\"secret\">[A-Za-z0-9_-]{43}</code>").containsMatchIn(answer))
        // From then on, one of the devices.
        assertContains(alice.page("/me"), "<span class=\"title\">AntennaPod</span>")
    }

    @Test
    fun avatarsAreChosenWithAClick() = testApplication {
        podcastServer()
        val alice = browser()
        alice.login()

        // Before any choice, the one taken from the username, in the header and checked in the picker.
        val before = alice.page("/me")
        val default = Avatar.default(TestSeed.ALICE)
        assertContains(before, "class=\"avatar small\"")
        assertContains(before, "value=\"${default.id}\" checked")
        assertEquals(Avatar.entries.size, Regex("name=\"avatar\"").findAll(before).count())
        // The picker saves as soon as one is clicked.
        assertContains(before, "hx-trigger=\"change\"")

        assertContains(alice.htmx(HttpMethod.Patch, "/me", "avatar" to "rocket").bodyAsText(), "Profil enregistré")
        val after = alice.page("/me")
        assertContains(after, "value=\"rocket\" checked")
        assertContains(after, Avatar.Rocket.emoji)
    }

    @Test
    fun searchTagsAndListeningInTheThinClient() = testApplication {
        podcastServer()
        val alice = browser()
        alice.login()

        // Tags typed with the note, then the library filtered by one, and reloaded with its filters.
        alice.htmx(HttpMethod.Patch, "/subscriptions/2", "note" to "", "tags" to "tech, english")
        val library = alice.page("/subscriptions?tag=tech")
        assertEquals(1, Regex("<li class=\"subscription\">").findAll(library).count())
        assertContains(library, "<span class=\"title\">Talking Kotlin</span>")
        assertContains(library, "hx-get=\"/subscriptions?tag=tech\"")
        // The tags to filter by follow the changes too.
        assertContains(library, "id=\"library-filters\" hx-get=\"/subscriptions?tag=tech\"")
        assertContains(library, "value=\"english, tech\"")
        // Filters that hide every subscription say so, rather than "no subscriptions yet".
        assertContains(alice.page("/subscriptions?q=nothing"), "Aucun abonnement ne correspond.")

        // The episode search, every criterion in one form sent by GET.
        val search = alice.page("/episodes?q=kotlin&status=NEW&favorite=false&minRating=0")
        assertContains(search, "2 épisodes")
        assertContains(search, "Compose Multiplatform for iOS is stable")
        assertContains(search, "name=\"from\"")

        // My listening, in Settings.
        assertContains(alice.page("/me"), "Rien d'écouté pour l'instant.")
        alice.htmx(HttpMethod.Post, "/episodes/7/played")
        val settings = alice.page("/me")
        assertContains(settings, "Mon écoute")
        assertContains(settings, "1 épisode terminé")
        assertContains(settings, "Fragmented")
    }
}
