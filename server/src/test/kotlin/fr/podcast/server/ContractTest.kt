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

    /** Thin client pages and documentation: HTML only, outside the JSON API contract. */
    private val outsideContract = listOf("/login", "/logout", "/fragments/", "/docs")

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
        check(post, "/episodes/1/download", "/episodes/{episodeId}/download", HttpStatusCode.Accepted)
        check(post, "/episodes/999/download", "/episodes/{episodeId}/download", HttpStatusCode.NotFound)
        services.media.downloadNext()
        check(get, "/episodes/1/audio", "/episodes/{episodeId}/audio", HttpStatusCode.OK, accept = ContentType.Any)
        check(get, "/episodes/1/audio", "/episodes/{episodeId}/audio", HttpStatusCode.PartialContent, accept = ContentType.Any,
            headers = mapOf(HttpHeaders.Range to "bytes=0-99"))
        check(put, "/episodes/2/kept", "/episodes/{episodeId}/kept", HttpStatusCode.NoContent)
        check(put, "/episodes/999/kept", "/episodes/{episodeId}/kept", HttpStatusCode.NotFound)
        check(delete, "/episodes/2/kept", "/episodes/{episodeId}/kept", HttpStatusCode.NoContent)

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
