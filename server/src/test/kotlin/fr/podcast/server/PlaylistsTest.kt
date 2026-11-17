package fr.podcast.server

import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Playlists (ADR 0010). Seed episodes, most recent first (see TestSeed): 1 Compose Multiplatform
 * (Talking Kotlin), 2 LCC 330 Kotlin 2.4 (Les Cast Codeurs, Alice in progress), 3 KMP in production,
 * 4 Qui a inventé le copier-coller ? (Alice in progress), 5 Ktor 3 (Talking Kotlin, played by Alice),
 * 6 LCC 329, 7 Gradle, 8 Le premier bug informatique, 9 Kotlin Multiplatform at scale (Talking Kotlin).
 */
class PlaylistsTest {

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

    private suspend fun HttpClient.create(token: String, body: String): JsonObject {
        val response = send(HttpMethod.Post, "/playlists", token, body)
        assertEquals(HttpStatusCode.Created, response.status, body)
        return response.json()
    }

    private fun JsonObject.episodeIds(): List<String> =
        this["_embedded"]!!.jsonObject["episodes"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

    private fun JsonObject.id() = this["id"]!!.jsonPrimitive.content

    @Test
    fun aStaticPlaylistKeepsTheChosenOrder() = testApplication {
        podcastServer()
        val alice = client.token()
        val playlist = client.create(alice, """{"title":"Pour le train","description":"Les trajets du lundi"}""")
        assertEquals("STATIC", playlist["kind"]!!.jsonPrimitive.content)
        val id = playlist.id()

        suspend fun add(body: String) = client.send(HttpMethod.Post, "/playlists/$id/episodes", alice, body)
        add("""{"episodeId":"3"}""")
        add("""{"episodeId":"1"}""")
        val added = add("""{"episodeId":"6","position":0}""").json()
        assertEquals(listOf("6", "3", "1"), added.episodeIds())
        assertEquals(3, added["episodeCount"]!!.jsonPrimitive.content.toInt())

        client.send(HttpMethod.Put, "/playlists/$id/episodes/1", alice, """{"position":0}""")
        assertEquals(HttpStatusCode.NoContent, client.send(HttpMethod.Delete, "/playlists/$id/episodes/6", alice).status)
        add("""{"episodeId":"3"}""") // already there: it moves to the end, without a copy
        val read = client.getJson("/playlists/$id", alice)
        assertEquals(listOf("1", "3"), read.episodeIds())

        // Each episode shows the reader's own state, with the playlist's actions on it.
        val first = read["_embedded"]!!.jsonObject["episodes"]!!.jsonArray.first().jsonObject
        assertTrue(setOf("remove-from-playlist", "move-in-playlist").all { it in first["_templates"]!!.jsonObject })
        assertEquals(setOf("update-playlist", "add-episode", "share", "publish-feed", "delete-playlist"), read["_templates"]!!.jsonObject.keys)

        // Refusals.
        assertEquals(HttpStatusCode.UnprocessableEntity, add("""{"episodeId":"999"}""").status)
        assertEquals(HttpStatusCode.UnprocessableEntity, add("""{"episodeId":"2","position":-1}""").status)
        assertEquals(HttpStatusCode.NotFound, client.send(HttpMethod.Put, "/playlists/$id/episodes/9", alice, """{"position":0}""").status)
        val bob = client.token(TestSeed.BOB, TestSeed.BOB_PASSWORD)
        assertEquals(HttpStatusCode.NotFound, client.send(HttpMethod.Get, "/playlists/$id", bob).status) // not shared

        val renamed = client.send(HttpMethod.Patch, "/playlists/$id", alice, """{"title":"Train"}""").json()
        assertEquals("Train", renamed["title"]!!.jsonPrimitive.content)
        assertEquals("Les trajets du lundi", renamed["description"]!!.jsonPrimitive.content) // absent: kept
        assertEquals(listOf("Train"), client.getJson("/playlists", alice)["_embedded"]!!.jsonObject["playlists"]!!.jsonArray
            .map { it.jsonObject["title"]!!.jsonPrimitive.content })

        assertEquals(HttpStatusCode.NoContent, client.send(HttpMethod.Delete, "/playlists/$id", alice).status)
        assertEquals(HttpStatusCode.NotFound, client.send(HttpMethod.Get, "/playlists/$id", alice).status)
    }

    @Test
    fun aDynamicPlaylistFollowsItsRulesOnItsOwnersLibrary() = testApplication {
        podcastServer()
        val alice = client.token()
        suspend fun episodes(rules: String) = client.create(alice, """{"title":"Auto","kind":"DYNAMIC",$rules}""").episodeIds()

        // Keywords match the title or the podcast (Talking Kotlin), whatever the case.
        assertEquals(listOf("1", "2", "5", "9"), episodes(""""keywords":"KOTLIN""""))
        assertEquals(listOf("1", "2", "9"), episodes(""""keywords":"kotlin","unplayedOnly":true"""))
        assertEquals(listOf("9", "5", "2", "1"), episodes(""""keywords":"kotlin","order":"OLDEST""""))
        assertEquals(listOf("1", "2"), episodes(""""keywords":"kotlin","limit":2"""))
        assertEquals(listOf("4", "7"), episodes(""""keywords":"copier-coller, gradle""""))
        assertEquals(emptyList(), episodes(""""keywords":"c++ (beta)"""")) // a keyword, not an expression

        // Favorites and ratings: Alice's own marks.
        client.send(HttpMethod.Put, "/episodes/7/favorite", alice)
        client.send(HttpMethod.Put, "/episodes/4/favorite", alice)
        client.send(HttpMethod.Put, "/episodes/6/rating", alice, """{"rating":5}""")
        client.send(HttpMethod.Put, "/episodes/8/rating", alice, """{"rating":3}""")
        assertEquals(listOf("4", "7"), episodes(""""favoritesOnly":true"""))
        assertEquals(listOf("6"), episodes(""""minRating":4"""))

        // A dynamic playlist is computed: its episodes do not change one by one.
        val auto = client.create(alice, """{"title":"Tout","kind":"DYNAMIC"}""")
        assertEquals(9, auto["episodeCount"]!!.jsonPrimitive.content.toInt())
        assertEquals(HttpStatusCode.Conflict, client.send(HttpMethod.Post, "/playlists/${auto.id()}/episodes", alice, """{"episodeId":"1"}""").status)
        assertEquals(HttpStatusCode.Conflict, client.send(HttpMethod.Post, "/playlists/${auto.id()}/shuffle", alice).status) // not random
        val narrowed = client.send(HttpMethod.Patch, "/playlists/${auto.id()}", alice, """{"keywords":"bug","order":"OLDEST"}""").json()
        assertEquals(listOf("8"), narrowed.episodeIds())
        assertEquals("OLDEST", narrowed["rules"]!!.jsonObject["order"]!!.jsonPrimitive.content)
    }

    @Test
    fun aRandomOrderHoldsUntilTheNextShuffle() = testApplication {
        podcastServer()
        val alice = client.token()
        val random = client.create(alice, """{"title":"Surprise","kind":"DYNAMIC","order":"RANDOM"}""")
        val id = random.id()
        val order = random.episodeIds()
        assertEquals((1..9).map { it.toString() }.toSet(), order.toSet())
        assertEquals(order, client.getJson("/playlists/$id", alice).episodeIds()) // stable
        assertTrue("shuffle" in random["_templates"]!!.jsonObject)

        // Another seed gives another order (tried a few times: two seeds may give the same one).
        val shuffled = (1..5).map { client.send(HttpMethod.Post, "/playlists/$id/shuffle", alice).json().episodeIds() }
        assertTrue(shuffled.any { it != order })
        assertNotEquals(emptyList(), shuffled.last())
    }

    @Test
    fun invalidPlaylistsAreExplained() = testApplication {
        podcastServer()
        val alice = client.token()
        suspend fun fields(body: String): List<String> {
            val response = client.send(HttpMethod.Post, "/playlists", alice, body)
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status, body)
            return response.json()["errors"]!!.jsonArray.map { it.jsonObject["field"]!!.jsonPrimitive.content }
        }
        assertEquals(listOf("title"), fields("""{"title":"  "}"""))
        assertEquals(listOf("kind"), fields("""{"title":"X","kind":"SMART"}"""))
        assertEquals(listOf("order"), fields("""{"title":"X","kind":"DYNAMIC","order":"SHUFFLE"}"""))
        assertEquals(listOf("minRating", "limit"), fields("""{"title":"X","kind":"DYNAMIC","minRating":9,"limit":0}"""))
        assertEquals(listOf("minRating"), fields("""{"title":"X","kind":"DYNAMIC","minRating":"many"}"""))
        assertEquals(listOf("keywords"), fields("""{"title":"X","kind":"DYNAMIC","keywords":"${(1..11).joinToString(",") { "k$it" }}"}"""))
    }
}
