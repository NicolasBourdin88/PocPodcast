package fr.podcast.server

import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.dialect.Dialects
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.plugin
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.PathSegmentConstantRouteSelector
import io.ktor.server.routing.PathSegmentParameterRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.testing.testApplication
import fr.podcast.server.media.AudioLinks
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.hours
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.dataformat.yaml.YAMLMapper
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Keeps the OpenAPI specification and the server in sync:
 * every route is documented, every documented operation is exercised,
 * and every response matches the status codes and schemas of the specification.
 */
class ContractTest {

    private val specResource = "openapi/podcast-api.yaml"
    private val spec: JsonNode = YAMLMapper().readTree(javaClass.classLoader.getResourceAsStream(specResource))
    private val schemas = SchemaRegistry.withDefaultDialect(Dialects.getOpenApi31())
    private val json = ObjectMapper()

    /** Thin client pages, its player script and documentation: outside the JSON API contract. */
    private val outsideContract = listOf("/login", "/logout", "/fragments/", "/docs", "/static")

    private data class Operation(val method: HttpMethod, val path: String) {
        override fun toString() = "${method.value} $path"
    }

    /** `/episodes/{episodeId}` and `/episodes/{id}` are the same route. */
    private fun normalize(path: String) = path.replace(Regex("\\{[^}]+}"), "{}")

    private val documented: Set<Operation> = buildSet {
        for ((path, item) in spec["paths"].properties()) {
            for ((method, _) in item.properties()) {
                if (method in setOf("get", "post", "put", "patch", "delete")) add(Operation(HttpMethod.parse(method.uppercase()), path))
            }
        }
    }

    @BeforeTest
    fun reset() = TestDatabase.reset()

    @Test
    fun everyRouteIsDocumentedAndEveryOperationExists() = testApplication {
        podcastServer()
        startApplication()

        val routes = application.plugin(RoutingRoot).getAllRoutes()
            .mapNotNull { it.operation() }
            .filterNot { operation -> outsideContract.any { operation.path.startsWith(it) } }
            .map { Operation(it.method, normalize(it.path)) }
            .toSet()
        val specified = documented.map { Operation(it.method, normalize(it.path)) }.toSet()

        assertEquals(emptySet(), routes - specified, "Routes missing from $specResource")
        assertEquals(emptySet(), specified - routes, "Documented operations without a route")
    }

    @Test
    fun responsesMatchTheSpecification() = testApplication {
        val services = podcastServer()
        val noRedirects = createClient { followRedirects = false }
        val alice = client.token()
        val disposable = client.token()
        val bob = client.token(TestSeed.BOB, TestSeed.BOB_PASSWORD)
        val exercised = mutableSetOf<Operation>()

        suspend fun check(
            method: HttpMethod, path: String, documentedPath: String, expected: HttpStatusCode,
            token: String? = alice, body: String? = null, accept: ContentType = HalForms, http: HttpClient = client,
            bodyType: ContentType = ContentType.Application.Json,
            headers: Map<String, String> = emptyMap(),
        ) {
            val response = http.request(path) {
                this.method = method
                token?.let { bearerAuth(it) }
                accept(accept)
                headers.forEach { (name, value) -> header(name, value) }
                body?.let { contentType(bodyType); setBody(it) }
            }
            val operation = Operation(method, documentedPath)
            assertEquals(expected, response.status, "$operation ($path)")
            exercised += operation

            val responseSpec = resolve(spec["paths"][documentedPath][method.value.lowercase()]["responses"][expected.value.toString()])
                ?: fail("$operation: status ${expected.value} is not documented")
            val text = response.bodyAsText()
            if (text.isEmpty()) return
            val mediaType = response.contentType()!!.withoutParameters().toString()
            val content = responseSpec["content"]
            val schema = (content?.get(mediaType) ?: content?.get(mediaType.substringBefore('/') + "/*"))?.get("schema")
                ?: fail("$operation: $mediaType is not documented for ${expected.value}")
            val ref = schema["\$ref"]?.asString() ?: return // inline schemas (HTML) are not validated
            val errors = schemas.getSchema(SchemaLocation.of("classpath:$specResource$ref")).validate(json.readTree(text))
            assertTrue(errors.isEmpty(), "$operation ($path) does not match $ref:\n${errors.joinToString("\n")}\n$text")
        }

        val get = HttpMethod.Get
        val post = HttpMethod.Post
        val put = HttpMethod.Put
        val delete = HttpMethod.Delete
        val patch = HttpMethod.Patch

        check(get, "/", "/", HttpStatusCode.OK, token = null)
        check(get, "/", "/", HttpStatusCode.OK)
        check(get, "/", "/", HttpStatusCode.OK, accept = ContentType("application", "hal+json"))

        check(post, "/auth/tokens", "/auth/tokens", HttpStatusCode.Created, token = null,
            body = """{"username":"${TestSeed.BOB}","password":"${TestSeed.BOB_PASSWORD}","deviceName":"contract"}""")
        check(post, "/auth/tokens", "/auth/tokens", HttpStatusCode.Unauthorized, token = null,
            body = """{"username":"bob","password":"wrong","deviceName":"contract"}""")
        check(post, "/auth/tokens", "/auth/tokens", HttpStatusCode.UnprocessableEntity, token = null, body = """{"username":"bob"}""")
        check(delete, "/auth/tokens/current", "/auth/tokens/current", HttpStatusCode.NoContent, token = disposable)
        check(delete, "/auth/tokens/current", "/auth/tokens/current", HttpStatusCode.Unauthorized, token = disposable)

        check(get, "/me", "/me", HttpStatusCode.OK)
        check(get, "/me", "/me", HttpStatusCode.Unauthorized, token = null)
        check(patch, "/me", "/me", HttpStatusCode.OK, body = """{"displayName":"Alice"}""")
        check(patch, "/me", "/me", HttpStatusCode.UnprocessableEntity, body = """{"displayName":""}""")
        check(put, "/me/password", "/me/password", HttpStatusCode.NoContent,
            body = """{"currentPassword":"${TestSeed.ALICE_PASSWORD}","newPassword":"${TestSeed.ALICE_PASSWORD}"}""")
        check(put, "/me/password", "/me/password", HttpStatusCode.UnprocessableEntity, body = """{"currentPassword":"wrong","newPassword":"long-enough"}""")
        val laptop = client.token()
        val laptopId = client.getJson("/me/devices", laptop)["_embedded"]!!.jsonObject["devices"]!!.jsonArray
            .map { it.jsonObject }.first { it["current"]!!.jsonPrimitive.content == "true" }["id"]!!.jsonPrimitive.content
        check(get, "/me/devices", "/me/devices", HttpStatusCode.OK)
        check(delete, "/me/devices/$laptopId", "/me/devices/{deviceId}", HttpStatusCode.NoContent)
        check(delete, "/me/devices/$laptopId", "/me/devices/{deviceId}", HttpStatusCode.NotFound)

        // Users 1 = Alice (administrator), 2 = Bob, 3 = Carol, created here.
        val carol = """{"username":"carol","displayName":"Carol","password":"carol-password"}"""
        check(get, "/users", "/users", HttpStatusCode.OK)
        check(post, "/users", "/users", HttpStatusCode.Created, body = carol)
        check(post, "/users", "/users", HttpStatusCode.UnprocessableEntity, body = carol)
        check(post, "/users", "/users", HttpStatusCode.Forbidden, token = bob, body = carol)
        check(get, "/users/3", "/users/{userId}", HttpStatusCode.OK)
        check(get, "/users/999", "/users/{userId}", HttpStatusCode.NotFound)
        check(patch, "/users/3", "/users/{userId}", HttpStatusCode.OK, body = """{"displayName":"Caroline"}""")
        check(patch, "/users/1", "/users/{userId}", HttpStatusCode.UnprocessableEntity, body = """{"role":"USER"}""")
        check(patch, "/users/3", "/users/{userId}", HttpStatusCode.Forbidden, token = bob, body = """{"role":"ADMIN"}""")
        check(patch, "/users/999", "/users/{userId}", HttpStatusCode.NotFound, body = """{"displayName":"X"}""")
        check(put, "/users/3/password", "/users/{userId}/password", HttpStatusCode.NoContent, body = """{"password":"carol-new-password"}""")
        check(put, "/users/3/password", "/users/{userId}/password", HttpStatusCode.UnprocessableEntity, body = """{"password":"short"}""")
        check(put, "/users/3/password", "/users/{userId}/password", HttpStatusCode.Forbidden, token = bob, body = """{"password":"long-enough"}""")
        check(put, "/users/999/password", "/users/{userId}/password", HttpStatusCode.NotFound, body = """{"password":"long-enough"}""")
        check(delete, "/users/1", "/users/{userId}", HttpStatusCode.Forbidden)
        check(delete, "/users/3", "/users/{userId}", HttpStatusCode.NoContent)
        check(delete, "/users/3", "/users/{userId}", HttpStatusCode.NotFound)
        check(get, "/subscriptions", "/subscriptions", HttpStatusCode.OK)
        check(get, "/subscriptions/1", "/subscriptions/{subscriptionId}", HttpStatusCode.OK)
        check(get, "/subscriptions/999", "/subscriptions/{subscriptionId}", HttpStatusCode.NotFound)

        check(get, "/podcasts/1", "/podcasts/{podcastId}", HttpStatusCode.OK)
        check(get, "/podcasts/999", "/podcasts/{podcastId}", HttpStatusCode.NotFound)
        check(get, "/podcasts/1/episodes?limit=1", "/podcasts/{podcastId}/episodes", HttpStatusCode.OK)
        check(get, "/podcasts/1/episodes?limit=0", "/podcasts/{podcastId}/episodes", HttpStatusCode.BadRequest)
        check(get, "/podcasts/999/episodes", "/podcasts/{podcastId}/episodes", HttpStatusCode.NotFound)

        check(get, "/episodes?limit=3", "/episodes", HttpStatusCode.OK)
        check(get, "/episodes?status=IN_PROGRESS", "/episodes", HttpStatusCode.OK)
        check(get, "/episodes?status=LATER", "/episodes", HttpStatusCode.BadRequest)
        check(get, "/episodes/2", "/episodes/{episodeId}", HttpStatusCode.OK)
        check(get, "/episodes/999", "/episodes/{episodeId}", HttpStatusCode.NotFound)

        check(put, "/episodes/1/progress", "/episodes/{episodeId}/progress", HttpStatusCode.NoContent, body = """{"positionSeconds":60}""")
        check(put, "/episodes/1/progress", "/episodes/{episodeId}/progress", HttpStatusCode.UnprocessableEntity, body = """{"positionSeconds":-1}""")
        check(put, "/episodes/999/progress", "/episodes/{episodeId}/progress", HttpStatusCode.NotFound, body = """{"positionSeconds":1}""")
        check(post, "/episodes/3/played", "/episodes/{episodeId}/played", HttpStatusCode.NoContent)
        check(post, "/episodes/999/played", "/episodes/{episodeId}/played", HttpStatusCode.NotFound)
        check(delete, "/episodes/3/played", "/episodes/{episodeId}/played", HttpStatusCode.NoContent)
        check(get, "/episodes/1/audio", "/episodes/{episodeId}/audio", HttpStatusCode.Found, http = noRedirects)
        // Signed links: players open them without credentials (ADR 0008).
        val signed = client.getJson("/episodes/1", alice)["_links"]!!.jsonObject["audio"]!!.jsonObject["href"]!!.jsonPrimitive.content
        check(get, signed, "/episodes/{episodeId}/audio", HttpStatusCode.Found, token = null, http = noRedirects)
        check(get, "/episodes/1/audio", "/episodes/{episodeId}/audio", HttpStatusCode.Unauthorized, token = null, http = noRedirects)
        val key = TestDatabase.database.query { secretsQueries.secret("audio-links").executeAsOne() }
        check(get, AudioLinks(key, validity = (-2).hours).path("1"), "/episodes/{episodeId}/audio", HttpStatusCode.Forbidden,
            token = null, http = noRedirects)
        check(post, "/episodes/1/download", "/episodes/{episodeId}/download", HttpStatusCode.Accepted)
        check(post, "/episodes/999/download", "/episodes/{episodeId}/download", HttpStatusCode.NotFound)
        services.media.downloadNext()
        check(get, "/episodes/1/audio", "/episodes/{episodeId}/audio", HttpStatusCode.OK, accept = ContentType.Any)
        check(get, "/episodes/1/audio", "/episodes/{episodeId}/audio", HttpStatusCode.PartialContent, accept = ContentType.Any,
            headers = mapOf(HttpHeaders.Range to "bytes=0-99"))
        check(put, "/episodes/2/kept", "/episodes/{episodeId}/kept", HttpStatusCode.NoContent)
        check(put, "/episodes/999/kept", "/episodes/{episodeId}/kept", HttpStatusCode.NotFound)
        check(delete, "/episodes/2/kept", "/episodes/{episodeId}/kept", HttpStatusCode.NoContent)
        check(put, "/episodes/2/favorite", "/episodes/{episodeId}/favorite", HttpStatusCode.NoContent)
        check(put, "/episodes/999/favorite", "/episodes/{episodeId}/favorite", HttpStatusCode.NotFound)
        check(put, "/episodes/2/rating", "/episodes/{episodeId}/rating", HttpStatusCode.NoContent, body = """{"rating":4}""")
        check(put, "/episodes/2/rating", "/episodes/{episodeId}/rating", HttpStatusCode.UnprocessableEntity, body = """{"rating":6}""")
        check(put, "/episodes/999/rating", "/episodes/{episodeId}/rating", HttpStatusCode.NotFound, body = """{"rating":4}""")
        check(get, "/episodes/2", "/episodes/{episodeId}", HttpStatusCode.OK) // favorite, rated: `unfavorite`, `clear-rating`
        check(delete, "/episodes/2/rating", "/episodes/{episodeId}/rating", HttpStatusCode.NoContent)
        check(delete, "/episodes/2/favorite", "/episodes/{episodeId}/favorite", HttpStatusCode.NoContent)

        check(post, "/subscriptions", "/subscriptions", HttpStatusCode.Created, body = """{"feedUrl":"https://feeds.test/podcast20.xml"}""")
        check(post, "/subscriptions", "/subscriptions", HttpStatusCode.OK, body = """{"feedUrl":"https://feeds.test/podcast20.xml"}""")
        check(post, "/subscriptions", "/subscriptions", HttpStatusCode.UnprocessableEntity, body = """{"feedUrl":"https://feeds.test/not-a-feed.html"}""")
        check(get, "/subscriptions", "/subscriptions", HttpStatusCode.OK) // with the Podcast 2.0 feed
        check(get, "/podcasts/5/episodes", "/podcasts/{podcastId}/episodes", HttpStatusCode.OK) // chapters, transcripts
        check(patch, "/subscriptions/1", "/subscriptions/{subscriptionId}", HttpStatusCode.OK, body = """{"note":"Le jeudi","autoDownload":true}""")
        check(patch, "/subscriptions/1", "/subscriptions/{subscriptionId}", HttpStatusCode.UnprocessableEntity, body = """{"autoDownload":"maybe"}""")
        check(patch, "/subscriptions/999", "/subscriptions/{subscriptionId}", HttpStatusCode.NotFound, body = """{"note":null}""")
        check(post, "/podcasts/5/refresh", "/podcasts/{podcastId}/refresh", HttpStatusCode.OK)
        check(post, "/podcasts/999/refresh", "/podcasts/{podcastId}/refresh", HttpStatusCode.NotFound)
        val opml = ContentType("text", "x-opml")
        check(get, "/search?q=kotlin", "/search", HttpStatusCode.OK)
        check(get, "/search?q=k", "/search", HttpStatusCode.BadRequest)
        check(get, "/search?q=panne", "/search", HttpStatusCode.BadGateway)
        check(get, "/subscriptions/opml", "/subscriptions/opml", HttpStatusCode.OK)
        check(post, "/subscriptions/opml", "/subscriptions/opml", HttpStatusCode.OK, bodyType = opml,
            body = javaClass.getResourceAsStream("/feeds/import.opml")!!.readBytes().decodeToString())
        check(post, "/subscriptions/opml", "/subscriptions/opml", HttpStatusCode.UnprocessableEntity, bodyType = opml, body = "<rss/>")
        check(delete, "/subscriptions/3", "/subscriptions/{subscriptionId}", HttpStatusCode.NoContent)
        check(delete, "/subscriptions/3", "/subscriptions/{subscriptionId}", HttpStatusCode.NotFound)

        check(get, "/queue", "/queue", HttpStatusCode.OK)
        check(post, "/queue", "/queue", HttpStatusCode.OK, body = """{"episodeId":"1"}""")
        check(post, "/queue", "/queue", HttpStatusCode.OK, body = """{"episodeId":"2","position":0}""")
        check(post, "/queue", "/queue", HttpStatusCode.UnprocessableEntity, body = """{"episodeId":"999"}""")
        check(get, "/queue", "/queue", HttpStatusCode.OK) // with episodes and `clear-queue`
        check(put, "/queue/1", "/queue/{episodeId}", HttpStatusCode.OK, body = """{"position":0}""")
        check(put, "/queue/1", "/queue/{episodeId}", HttpStatusCode.UnprocessableEntity, body = """{"position":-1}""")
        check(put, "/queue/5", "/queue/{episodeId}", HttpStatusCode.NotFound, body = """{"position":0}""")
        check(get, "/episodes/1", "/episodes/{episodeId}", HttpStatusCode.OK) // queued: `dequeue` and `move`
        check(delete, "/queue/2", "/queue/{episodeId}", HttpStatusCode.NoContent)
        check(delete, "/queue/2", "/queue/{episodeId}", HttpStatusCode.NotFound)
        check(delete, "/queue", "/queue", HttpStatusCode.NoContent)

        // Sharing (ADR 0009): Alice shares Les Cast Codeurs (subscription 1) with Bob; 2 stays hers.
        check(get, "/subscriptions/1/shares", "/subscriptions/{subscriptionId}/shares", HttpStatusCode.OK)
        check(post, "/subscriptions/1/shares", "/subscriptions/{subscriptionId}/shares", HttpStatusCode.OK,
            body = """{"userId":"2","permission":"READ","message":"Pour toi"}""")
        check(post, "/subscriptions/1/shares", "/subscriptions/{subscriptionId}/shares", HttpStatusCode.UnprocessableEntity, body = """{"userId":"999"}""")
        check(post, "/subscriptions/1/shares", "/subscriptions/{subscriptionId}/shares", HttpStatusCode.Forbidden, token = bob, body = """{"userId":"1"}""")
        check(post, "/subscriptions/2/shares", "/subscriptions/{subscriptionId}/shares", HttpStatusCode.NotFound, token = bob, body = """{"userId":"1"}""")
        check(get, "/subscriptions/1/shares", "/subscriptions/{subscriptionId}/shares", HttpStatusCode.Forbidden, token = bob)
        check(get, "/subscriptions/2/shares", "/subscriptions/{subscriptionId}/shares", HttpStatusCode.NotFound, token = bob)
        check(get, "/subscriptions/1", "/subscriptions/{subscriptionId}", HttpStatusCode.OK, token = bob) // read-only view
        check(get, "/shared", "/shared", HttpStatusCode.OK, token = bob)
        val shareId = client.getJson("/shared", bob)["_embedded"]!!.jsonObject["shares"]!!.jsonArray.first().jsonObject["id"]!!.jsonPrimitive.content
        check(get, "/shares/$shareId", "/shares/{shareId}", HttpStatusCode.OK, token = bob)
        check(get, "/shares/999", "/shares/{shareId}", HttpStatusCode.NotFound)
        check(patch, "/shares/$shareId", "/shares/{shareId}", HttpStatusCode.Forbidden, token = bob, body = """{"permission":"WRITE"}""")
        check(patch, "/shares/$shareId", "/shares/{shareId}", HttpStatusCode.OK, body = """{"permission":"WRITE"}""")
        check(patch, "/shares/$shareId", "/shares/{shareId}", HttpStatusCode.UnprocessableEntity, body = """{"permission":"OWNER"}""")
        check(patch, "/shares/999", "/shares/{shareId}", HttpStatusCode.NotFound, body = """{"permission":"READ"}""")
        check(patch, "/subscriptions/1", "/subscriptions/{subscriptionId}", HttpStatusCode.OK, token = bob, body = """{"note":"Partagé"}""")
        check(delete, "/subscriptions/1", "/subscriptions/{subscriptionId}", HttpStatusCode.Forbidden, token = bob)
        check(post, "/episodes/2/shares", "/episodes/{episodeId}/shares", HttpStatusCode.OK, body = """{"userId":"2","message":"Écoute ça"}""")
        check(post, "/episodes/2/shares", "/episodes/{episodeId}/shares", HttpStatusCode.UnprocessableEntity, body = """{"userId":"1"}""")
        check(post, "/episodes/999/shares", "/episodes/{episodeId}/shares", HttpStatusCode.NotFound, body = """{"userId":"2"}""")
        check(get, "/shared", "/shared", HttpStatusCode.OK, token = bob) // with the recommended episode
        check(delete, "/shares/$shareId", "/shares/{shareId}", HttpStatusCode.NoContent, token = bob)
        check(delete, "/shares/$shareId", "/shares/{shareId}", HttpStatusCode.NotFound)

        // Playlists (ADR 0010): 1 static, 2 dynamic in random order, 3 deleted.
        val playlist = "/playlists/{playlistId}"
        val items = "/playlists/{playlistId}/episodes"
        val item = "/playlists/{playlistId}/episodes/{episodeId}"
        check(get, "/playlists", "/playlists", HttpStatusCode.OK)
        check(post, "/playlists", "/playlists", HttpStatusCode.Created, body = """{"title":"Pour le train"}""")
        check(post, "/playlists", "/playlists", HttpStatusCode.Created,
            body = """{"title":"Surprise","kind":"DYNAMIC","keywords":"kotlin","order":"RANDOM"}""")
        check(post, "/playlists", "/playlists", HttpStatusCode.UnprocessableEntity, body = """{"title":""}""")
        check(post, "/playlists/1/episodes", items, HttpStatusCode.OK, body = """{"episodeId":"1"}""")
        check(post, "/playlists/1/episodes", items, HttpStatusCode.OK, body = """{"episodeId":"2","position":0}""")
        check(post, "/playlists/1/episodes", items, HttpStatusCode.UnprocessableEntity, body = """{"episodeId":"999"}""")
        check(post, "/playlists/2/episodes", items, HttpStatusCode.Conflict, body = """{"episodeId":"1"}""")
        check(post, "/playlists/1/episodes", items, HttpStatusCode.NotFound, token = bob, body = """{"episodeId":"1"}""")
        check(get, "/playlists", "/playlists", HttpStatusCode.OK) // both
        check(get, "/playlists/1", playlist, HttpStatusCode.OK) // its episodes, with `remove-from-playlist` and `move-in-playlist`
        check(get, "/playlists/2", playlist, HttpStatusCode.OK)
        check(get, "/playlists/1", playlist, HttpStatusCode.NotFound, token = bob)
        check(put, "/playlists/1/episodes/1", item, HttpStatusCode.OK, body = """{"position":0}""")
        check(put, "/playlists/1/episodes/1", item, HttpStatusCode.UnprocessableEntity, body = """{"position":-1}""")
        check(put, "/playlists/1/episodes/9", item, HttpStatusCode.NotFound, body = """{"position":0}""")
        check(put, "/playlists/2/episodes/1", item, HttpStatusCode.Conflict, body = """{"position":0}""")
        check(patch, "/playlists/2", playlist, HttpStatusCode.OK, body = """{"keywords":"kotlin, ktor","minRating":0}""")
        check(patch, "/playlists/2", playlist, HttpStatusCode.UnprocessableEntity, body = """{"limit":500}""")
        check(patch, "/playlists/999", playlist, HttpStatusCode.NotFound, body = """{"title":"X"}""")
        check(post, "/playlists/2/shuffle", "/playlists/{playlistId}/shuffle", HttpStatusCode.OK)
        check(post, "/playlists/1/shuffle", "/playlists/{playlistId}/shuffle", HttpStatusCode.Conflict)
        check(delete, "/playlists/1/episodes/2", item, HttpStatusCode.NoContent)
        check(delete, "/playlists/1/episodes/2", item, HttpStatusCode.NotFound)
        check(delete, "/playlists/2/episodes/1", item, HttpStatusCode.Conflict)
        // Playlist 1 shared read-only with Bob.
        check(get, "/playlists/1/shares", "/playlists/{playlistId}/shares", HttpStatusCode.OK)
        check(post, "/playlists/1/shares", "/playlists/{playlistId}/shares", HttpStatusCode.OK, body = """{"userId":"2","message":"Pour le train"}""")
        check(post, "/playlists/1/shares", "/playlists/{playlistId}/shares", HttpStatusCode.UnprocessableEntity, body = """{"userId":"1"}""")
        check(post, "/playlists/1/shares", "/playlists/{playlistId}/shares", HttpStatusCode.Forbidden, token = bob, body = """{"userId":"1"}""")
        check(post, "/playlists/999/shares", "/playlists/{playlistId}/shares", HttpStatusCode.NotFound, body = """{"userId":"2"}""")
        check(get, "/playlists/1/shares", "/playlists/{playlistId}/shares", HttpStatusCode.OK) // with Bob
        check(get, "/playlists/1/shares", "/playlists/{playlistId}/shares", HttpStatusCode.Forbidden, token = bob)
        check(get, "/playlists/2/shares", "/playlists/{playlistId}/shares", HttpStatusCode.NotFound, token = bob)
        check(get, "/playlists/1", playlist, HttpStatusCode.OK, token = bob) // read-only
        check(post, "/playlists/1/episodes", items, HttpStatusCode.Forbidden, token = bob, body = """{"episodeId":"1"}""")
        check(get, "/shared", "/shared", HttpStatusCode.OK, token = bob) // with the playlist
        check(post, "/playlists", "/playlists", HttpStatusCode.Created, body = """{"title":"Éphémère"}""")
        check(delete, "/playlists/3", playlist, HttpStatusCode.NotFound, token = bob)
        check(delete, "/playlists/3", playlist, HttpStatusCode.NoContent)
        check(delete, "/playlists/3", playlist, HttpStatusCode.NotFound)

        // Five wrong passwords for Bob (one above): his sign-ins and password changes wait.
        repeat(4) {
            check(post, "/auth/tokens", "/auth/tokens", HttpStatusCode.Unauthorized, token = null,
                body = """{"username":"bob","password":"wrong","deviceName":"contract"}""")
        }
        check(post, "/auth/tokens", "/auth/tokens", HttpStatusCode.TooManyRequests, token = null,
            body = """{"username":"${TestSeed.BOB}","password":"${TestSeed.BOB_PASSWORD}","deviceName":"contract"}""")
        check(put, "/me/password", "/me/password", HttpStatusCode.TooManyRequests, token = bob,
            body = """{"currentPassword":"${TestSeed.BOB_PASSWORD}","newPassword":"long-enough"}""")

        val missing = documented - exercised
        assertTrue(missing.isEmpty(), "Operations never exercised by this test: $missing")
    }

    /** Follows a `$ref` to `#/components/responses/…`. */
    private fun resolve(node: JsonNode?): JsonNode? {
        val ref = node?.get("\$ref")?.asString() ?: return node
        return ref.removePrefix("#/").split('/').fold(spec) { current, key -> current[key] }
    }

    /** Method and path of a leaf route, or null for intermediate nodes. */
    private fun RoutingNode.operation(): Operation? {
        val method = (selector as? HttpMethodRouteSelector)?.method ?: return null
        val segments = generateSequence(this) { it.parent }.toList().reversed().mapNotNull { node ->
            when (val selector = node.selector) {
                is PathSegmentConstantRouteSelector -> selector.value
                is PathSegmentParameterRouteSelector -> "{${selector.name}}"
                else -> null
            }
        }
        return Operation(method, "/" + segments.joinToString("/"))
    }
}
