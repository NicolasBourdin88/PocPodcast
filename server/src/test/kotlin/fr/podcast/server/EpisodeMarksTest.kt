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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Favorites and ratings (ADR 0010): each user's own marks on an episode. */
class EpisodeMarksTest {

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

    @Test
    fun favoritesAndRatingsBelongToEachUser() = testApplication {
        podcastServer()
        val alice = client.token()
        val bob = client.token(TestSeed.BOB, TestSeed.BOB_PASSWORD)

        assertEquals(HttpStatusCode.NoContent, client.send(HttpMethod.Put, "/episodes/2/favorite", alice).status)
        assertEquals(HttpStatusCode.NoContent, client.send(HttpMethod.Put, "/episodes/2/rating", alice, """{"rating":4}""").status)

        val marked = client.getJson("/episodes/2", alice)
        assertTrue(marked["favorite"]!!.jsonPrimitive.boolean)
        assertEquals(4, marked["rating"]!!.jsonPrimitive.content.toInt())
        // The position is kept: marks do not touch the listening state.
        assertEquals(38 * 60, marked["positionSeconds"]!!.jsonPrimitive.content.toInt())
        val templates = marked["_templates"]!!.jsonObject
        assertTrue("unfavorite" in templates && "clear-rating" in templates && "favorite" !in templates)
        assertEquals("4", templates["rate"]!!.jsonObject["properties"].toString().substringAfter("\"value\":").substringBefore(","))

        // Bob's view of the same episode is untouched.
        val bobs = client.getJson("/episodes/2", bob)
        assertEquals(false, bobs["favorite"]!!.jsonPrimitive.boolean)
        assertEquals(JsonNull, bobs["rating"])

        assertEquals(HttpStatusCode.UnprocessableEntity, client.send(HttpMethod.Put, "/episodes/2/rating", alice, """{"rating":0}""").status)
        client.send(HttpMethod.Delete, "/episodes/2/rating", alice)
        client.send(HttpMethod.Delete, "/episodes/2/favorite", alice)
        val cleared = client.getJson("/episodes/2", alice)
        assertEquals(false, cleared["favorite"]!!.jsonPrimitive.boolean)
        assertEquals(JsonNull, cleared["rating"])
    }

    @Test
    fun aNewEpisodeCanBeMarkedBeforeBeingPlayed() = testApplication {
        podcastServer()
        val alice = client.token()

        client.send(HttpMethod.Put, "/episodes/1/favorite", alice)
        val episode = client.getJson("/episodes/1", alice)
        assertEquals("NEW", episode["status"]!!.jsonPrimitive.content)
        assertTrue(episode["favorite"]!!.jsonPrimitive.boolean)
    }
}
