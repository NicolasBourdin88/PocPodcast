package fr.podcast.server

import fr.podcast.shared.model.Avatar
import kotlinx.serialization.json.JsonNull
import fr.podcast.server.auth.Accounts
import fr.podcast.server.auth.Role
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Accounts (ADR 0009): Alice is the administrator of the test data, Bob a user. */
class AccountManagementTest {

    @BeforeTest
    fun reset() = TestDatabase.reset()

    private suspend fun HttpClient.send(method: HttpMethod, path: String, token: String, body: String? = null): HttpResponse = request(path) {
        this.method = method
        bearerAuth(token)
        accept(HalForms)
        body?.let {
            contentType(ContentType.Application.Json)
            setBody(it)
        }
    }

    private suspend fun HttpClient.signIn(username: String, password: String): HttpResponse = post("/auth/tokens") {
        contentType(ContentType.Application.Json)
        setBody("""{"username":"$username","password":"$password","deviceName":"test"}""")
    }

    private suspend fun field(response: HttpResponse): String {
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        return response.json()["errors"]!!.jsonArray.first().jsonObject["field"]!!.jsonPrimitive.content
    }

    private fun ApplicationTestBuilder.browser() = createClient {
        install(HttpCookies)
        followRedirects = false
    }

    @Test
    fun administratorsManageAccounts() = testApplication {
        podcastServer()
        val alice = client.token()
        val bob = client.token(TestSeed.BOB, TestSeed.BOB_PASSWORD)

        // Everyone reads the directory (to choose whom to share with); only administrators create accounts.
        val directory = client.getJson("/users", bob)
        val names = directory["_embedded"]!!.jsonObject["users"]!!.jsonArray.map { it.jsonObject["displayName"]!!.jsonPrimitive.content }
        assertEquals(listOf("Alice", "Bob"), names)
        assertFalse("_templates" in directory)
        assertTrue("create-user" in client.getJson("/users", alice)["_templates"]!!.jsonObject)
        val carolJson = """{"username":"carol","displayName":"Carol","password":"carol-password"}"""
        assertEquals(HttpStatusCode.Forbidden, client.send(HttpMethod.Post, "/users", bob, carolJson).status)

        val created = client.send(HttpMethod.Post, "/users", alice, carolJson)
        assertEquals(HttpStatusCode.Created, created.status)
        val carolId = created.json()["id"]!!.jsonPrimitive.content
        assertEquals("username", field(client.send(HttpMethod.Post, "/users", alice, carolJson))) // already taken
        assertEquals("username", field(client.send(HttpMethod.Post, "/users", alice, """{"username":"Carol Two","displayName":"C","password":"carol-password"}""")))
        assertEquals("password", field(client.send(HttpMethod.Post, "/users", alice, """{"username":"dave","displayName":"Dave","password":"short"}""")))
        val carol = client.token("carol", "carol-password")

        // Renamed and promoted; the last administrator cannot be demoted.
        client.send(HttpMethod.Patch, "/users/$carolId", alice, """{"displayName":"Caroline","role":"ADMIN"}""")
        val me = client.getJson("/me", carol)
        assertEquals("Caroline", me["displayName"]!!.jsonPrimitive.content)
        assertEquals("ADMIN", me["role"]!!.jsonPrimitive.content)
        client.send(HttpMethod.Patch, "/users/$carolId", alice, """{"role":"USER"}""")
        assertEquals("role", field(client.send(HttpMethod.Patch, "/users/1", alice, """{"role":"USER"}""")))

        // A reset by an administrator signs the account out everywhere.
        assertEquals(HttpStatusCode.NoContent, client.send(HttpMethod.Put, "/users/$carolId/password", alice, """{"password":"new-carol-password"}""").status)
        assertEquals(HttpStatusCode.Unauthorized, client.send(HttpMethod.Get, "/me", carol).status)
        client.token("carol", "new-carol-password")

        assertEquals(HttpStatusCode.Forbidden, client.send(HttpMethod.Delete, "/users/1", alice).status) // not their own
        assertEquals(HttpStatusCode.NoContent, client.send(HttpMethod.Delete, "/users/$carolId", alice).status)
        assertEquals(HttpStatusCode.NotFound, client.send(HttpMethod.Get, "/users/$carolId", alice).status)
    }

    @Test
    fun aDeletedAccountTakesThePodcastsNobodyElseFollows() = testApplication {
        podcastServer()
        val alice = client.token()
        val carolId = client.send(HttpMethod.Post, "/users", alice, """{"username":"carol","displayName":"Carol","password":"carol-password"}""")
            .json()["id"]!!.jsonPrimitive.content
        val carol = client.token("carol", "carol-password")
        val own = client.send(HttpMethod.Post, "/subscriptions", carol, """{"feedUrl":"https://feeds.test/podcast20.xml"}""")
        val ownPodcast = own.json()["_embedded"]!!.jsonObject["podcast"]!!.jsonObject["id"]!!.jsonPrimitive.content
        client.send(HttpMethod.Post, "/subscriptions", carol, """{"feedUrl":"https://feeds.test/cac.xml"}""") // also Alice's

        client.send(HttpMethod.Delete, "/users/$carolId", alice)

        assertEquals(HttpStatusCode.NotFound, client.send(HttpMethod.Get, "/podcasts/$ownPodcast", alice).status)
        assertEquals(HttpStatusCode.OK, client.send(HttpMethod.Get, "/podcasts/3", alice).status)
    }

    @Test
    fun usersChangeTheirPasswordAndSignOutTheirDevices() = testApplication {
        podcastServer()
        val phone = client.token(TestSeed.BOB, TestSeed.BOB_PASSWORD)
        val laptop = client.token(TestSeed.BOB, TestSeed.BOB_PASSWORD)

        val devices = client.getJson("/me/devices", laptop)["_embedded"]!!.jsonObject["devices"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, devices.size)
        assertEquals(1, devices.count { it["current"]!!.jsonPrimitive.boolean })
        val phoneId = devices.first { !it["current"]!!.jsonPrimitive.boolean }["id"]!!.jsonPrimitive.content

        // A lost phone is signed out from another device.
        assertEquals(HttpStatusCode.NoContent, client.send(HttpMethod.Delete, "/me/devices/$phoneId", laptop).status)
        assertEquals(HttpStatusCode.Unauthorized, client.send(HttpMethod.Get, "/me", phone).status)
        assertEquals(HttpStatusCode.NotFound, client.send(HttpMethod.Delete, "/me/devices/$phoneId", laptop).status)

        val browser = browser()
        browser.submitForm("/login", parameters { append("username", TestSeed.BOB); append("password", TestSeed.BOB_PASSWORD) })
        assertEquals(HttpStatusCode.OK, browser.get("/me") { accept(ContentType("application", "hal+json")) }.status)
        val tablet = client.token(TestSeed.BOB, TestSeed.BOB_PASSWORD)

        val change = { current: String, new: String -> """{"currentPassword":"$current","newPassword":"$new"}""" }
        assertEquals("currentPassword", field(client.send(HttpMethod.Put, "/me/password", laptop, change("wrong-password", "bob-new-password"))))
        assertEquals("newPassword", field(client.send(HttpMethod.Put, "/me/password", laptop, change(TestSeed.BOB_PASSWORD, "short"))))
        assertEquals(HttpStatusCode.NoContent, client.send(HttpMethod.Put, "/me/password", laptop, change(TestSeed.BOB_PASSWORD, "bob-new-password")).status)

        // The device that asked stays signed in; every other device and session signs in again.
        assertEquals(HttpStatusCode.OK, client.send(HttpMethod.Get, "/me", laptop).status)
        assertEquals(HttpStatusCode.Unauthorized, client.send(HttpMethod.Get, "/me", tablet).status)
        assertEquals(HttpStatusCode.Unauthorized, browser.get("/me") { accept(ContentType("application", "hal+json")) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.signIn(TestSeed.BOB, TestSeed.BOB_PASSWORD).status)
        client.token(TestSeed.BOB, "bob-new-password")

        val renamed = client.send(HttpMethod.Patch, "/me", laptop, """{"displayName":"Robert"}""").json()
        assertEquals("Robert", renamed["displayName"]!!.jsonPrimitive.content)
        val blank = client.send(HttpMethod.Patch, "/me", laptop, """{"displayName":"  "}""")
        assertEquals("between 1 and 100 characters", blank.json()["errors"]!!.jsonArray.single().jsonObject["message"]!!.jsonPrimitive.content)
        assertEquals("displayName", field(blank))

        // The avatar (ADR 0013): none at first, one of the catalog's, offered by the template, kept with the name.
        assertEquals(JsonNull, client.getJson("/me", laptop)["avatar"])
        val options = client.getJson("/me", laptop)["_templates"]!!.jsonObject["update-profile"]!!.jsonObject["properties"]!!.jsonArray
            .single { it.jsonObject["name"]!!.jsonPrimitive.content == "avatar" }.jsonObject["options"]!!.jsonObject["inline"]!!.jsonArray
        assertEquals(Avatar.ids, options.map { it.jsonPrimitive.content })
        val owl = client.send(HttpMethod.Patch, "/me", laptop, """{"avatar":"owl"}""").json()
        assertEquals("owl", owl["avatar"]!!.jsonPrimitive.content)
        assertEquals("Robert", owl["displayName"]!!.jsonPrimitive.content)
        assertEquals("avatar", field(client.send(HttpMethod.Patch, "/me", laptop, """{"avatar":"dragon"}""")))
        assertEquals("displayName", field(client.send(HttpMethod.Patch, "/me", laptop, "{}")))
        // Everyone sees it.
        val directory = client.getJson("/users", client.token())["_embedded"]!!.jsonObject["users"]!!.jsonArray.map { it.jsonObject }
        assertEquals("owl", directory.single { it["username"]!!.jsonPrimitive.content == TestSeed.BOB }["avatar"]!!.jsonPrimitive.content)
    }

    @Test
    fun repeatedWrongPasswordsAreSlowedDown() = testApplication {
        podcastServer()
        repeat(5) { assertEquals(HttpStatusCode.Unauthorized, client.signIn(TestSeed.BOB, "wrong-password").status) }

        // Even the right password waits now, for this account from this address.
        val locked = client.signIn(TestSeed.BOB, TestSeed.BOB_PASSWORD)
        assertEquals(HttpStatusCode.TooManyRequests, locked.status)
        assertTrue(locked.headers[HttpHeaders.RetryAfter]!!.toLong() in 1..900)
        assertEquals("/problems/too-many-attempts", locked.json()["type"]!!.jsonPrimitive.content)
        val form = browser().submitForm("/login", parameters { append("username", TestSeed.BOB); append("password", TestSeed.BOB_PASSWORD) })
        assertEquals(HttpStatusCode.TooManyRequests, form.status)

        client.token() // other accounts are not affected
    }

    @Test
    fun theFirstAdministratorComesFromTheEnvironment() = runBlocking<Unit> {
        TestDatabase.execute("TRUNCATE users RESTART IDENTITY CASCADE")
        val accounts = Accounts(TestDatabase.database)

        assertNull(bootstrapAdmin(accounts, null, null))
        assertEquals(Role.ADMIN, bootstrapAdmin(accounts, "root", "root-password")!!.role)
        assertNull(bootstrapAdmin(accounts, "other", "other-password")) // the server has an account now
    }
}
