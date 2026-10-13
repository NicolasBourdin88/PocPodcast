package poc.auth

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.basicAuth
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.http.setCookie
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthTest {

    private val world = World()
    private val alice = world.accounts.register("alice", "correct horse battery staple")
    private val bob = world.accounts.register("bob", "hunter2-but-longer")
    private val carol = world.accounts.register("carol", "another long password")
    private val playlist = Playlist(1, alice.id, "Trajet du matin").also { world.playlists[it.id] = it }

    private fun ApplicationTestBuilder.setUp(): HttpClient {
        application { pocModule(world) }
        return createClient { install(HttpCookies) }
    }

    private suspend fun HttpClient.tokenFor(name: String, password: String): String =
        post("/api/tokens") { basicAuth(name, password); setBody("test device") }.bodyAsText()

    @Test
    fun passwordsAreStoredAsArgon2idHashes() {
        assertTrue(alice.passwordHash.startsWith("\$argon2id\$"), alice.passwordHash)
        assertEquals(null, world.accounts.checkPassword("alice", "wrong"))
    }

    @Test
    fun thinClientUsesASessionCookie() = testApplication {
        val client = setUp()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/playlists/1").status)

        client.submitForm("/login", parameters { append("name", "alice"); append("password", "correct horse battery staple") })

        assertEquals("Trajet du matin", client.get("/api/playlists/1").bodyAsText())
    }

    @Test
    fun richClientsUseRevocableTokens() = testApplication {
        val client = setUp()
        val token = client.tokenFor("alice", "correct horse battery staple")

        assertEquals(HttpStatusCode.OK, client.get("/api/playlists/1") { bearerAuth(token) }.status)

        world.accounts.revoke(token) // e.g. phone lost: revoke that device only
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/playlists/1") { bearerAuth(token) }.status)
    }

    @Test
    fun gpodderClientsCanUseAnAppPasswordInsteadOfTheRealOne() = testApplication {
        val client = setUp()
        val appPassword = client.tokenFor("alice", "correct horse battery staple")

        val login = client.post("/api/2/auth/alice/login.json") { basicAuth("alice", appPassword) }

        assertEquals(HttpStatusCode.OK, login.status)
        assertTrue(login.setCookie().any { it.name == "sessionid" })
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/2/auth/alice/login.json") { basicAuth("alice", "wrong") }.status)
        // Another user's app password does not work for alice.
        val bobToken = client.tokenFor("bob", "hunter2-but-longer")
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/2/auth/alice/login.json") { basicAuth("alice", bobToken) }.status)
    }

    @Test
    fun sharingGivesReadOrWriteAccess() = testApplication {
        val client = setUp()
        val bobToken = client.tokenFor("bob", "hunter2-but-longer")
        val carolToken = client.tokenFor("carol", "another long password")

        // Not shared: the playlist does not exist for bob (404, not 403, to avoid leaking ids).
        assertEquals(HttpStatusCode.NotFound, client.get("/api/playlists/1") { bearerAuth(bobToken) }.status)

        world.sharing.share(playlist.resource, bob, Permission.READ)
        assertEquals(HttpStatusCode.OK, client.get("/api/playlists/1") { bearerAuth(bobToken) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.put("/api/playlists/1") { bearerAuth(bobToken); setBody("Piraté") }.status)

        world.sharing.share(playlist.resource, bob, Permission.WRITE)
        assertEquals(HttpStatusCode.NoContent, client.put("/api/playlists/1") { bearerAuth(bobToken); setBody("Trajet") }.status)
        assertEquals("Trajet", playlist.title)
        // WRITE does not allow deleting: only the owner can.
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/playlists/1") { bearerAuth(bobToken) }.status)

        assertEquals(HttpStatusCode.NotFound, client.get("/api/playlists/1") { bearerAuth(carolToken) }.status)

        world.sharing.unshare(playlist.resource, bob)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/playlists/1") { bearerAuth(bobToken) }.status)
    }
}
