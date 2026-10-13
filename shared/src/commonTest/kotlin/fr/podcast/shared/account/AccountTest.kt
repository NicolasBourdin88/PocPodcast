package fr.podcast.shared.account

import fr.podcast.shared.api.ApiException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AccountTest {

    private val anonymousRoot = """
        {"name":"Serveur B","apiVersion":1,"_links":{"self":{"href":"/"},"tokens":{"href":"/auth/tokens"}},
         "_templates":{"create-token":{"method":"POST","target":"/auth/tokens","contentType":"application/json",
           "properties":[{"name":"username","required":true},{"name":"password","required":true},{"name":"deviceName","required":true}]}}}
    """.trimIndent()

    private fun server(handler: (HttpRequestData) -> Pair<HttpStatusCode, String>) = HttpClient(
        MockEngine { request ->
            val (status, body) = handler(request)
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/prs.hal-forms+json"))
        },
    )

    @Test
    fun signInDiscoversHowToGetAToken() = runTest {
        val requests = mutableListOf<String>()
        val client = server { request ->
            requests += "${request.method.value} ${request.url.encodedPath}"
            when {
                request.url.encodedPath == "/" -> HttpStatusCode.OK to anonymousRoot
                request.method == HttpMethod.Post -> HttpStatusCode.Created to
                    """{"token":"secret","deviceName":"test","createdAt":"2026-10-13T08:00:00Z","_links":{"self":{"href":"/auth/tokens/current"},"me":{"href":"/me"}}}"""
                else -> HttpStatusCode.OK to """{"id":"2","username":"bob","displayName":"Bob","role":"USER","_links":{"self":{"href":"/me"}}}"""
            }
        }

        val account = AccountService(client, "test").signIn("localhost:8081/", "bob", "password")

        assertEquals(Account("http://localhost:8081", "Serveur B", "bob", "Bob", "secret"), account)
        assertEquals(listOf("GET /", "POST /auth/tokens", "GET /me"), requests)
    }

    @Test
    fun failuresAreExplained() = runTest {
        val service = { client: HttpClient -> AccountService(client, "test") }

        val wrongPassword = server { request ->
            if (request.method == HttpMethod.Post) HttpStatusCode.Unauthorized to "{}" else HttpStatusCode.OK to anonymousRoot
        }
        assertFailsWith<ApiException.Unauthorized> { service(wrongPassword).signIn("http://s", "bob", "nope") }

        val website = HttpClient(MockEngine { respond("<html>Hello</html>", HttpStatusCode.OK) })
        assertFailsWith<ApiException.NotAPodcastServer> { service(website).signIn("http://example.org", "bob", "x") }

        val down = HttpClient(MockEngine { respondError(HttpStatusCode.BadGateway) })
        assertFailsWith<ApiException.NotAPodcastServer> { service(down).signIn("http://s", "bob", "x") }

        val offline = HttpClient(MockEngine { throw IllegalStateException("connection refused") })
        assertFailsWith<ApiException.Unreachable> { service(offline).signIn("http://s", "bob", "x") }
    }

    @Test
    fun accountsArePersistedWithTheActiveOne() {
        val storage = InMemoryKeyValueStore()
        val alice = Account("http://a", "A", "alice", "Alice", "t1")
        val bob = Account("http://b", "B", "bob", "Bob", "t2")

        AccountStore(storage).apply {
            save(alice)
            save(bob)
            select(alice.id)
        }

        val reloaded = AccountStore(storage)
        assertEquals(listOf(alice, bob), reloaded.state.value.accounts)
        assertEquals(alice, reloaded.state.value.active)

        reloaded.remove(alice.id)
        assertEquals(bob, reloaded.state.value.active)
        reloaded.remove(bob.id)
        assertNull(reloaded.state.value.active)
    }

    @Test
    fun serverUrlsAreNormalized() {
        assertEquals("http://localhost:8080", normalizeServerUrl(" localhost:8080/ "))
        assertEquals("https://podcast.example.org", normalizeServerUrl("https://podcast.example.org"))
    }
}
