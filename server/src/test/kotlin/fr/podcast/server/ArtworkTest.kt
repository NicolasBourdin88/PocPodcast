package fr.podcast.server

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.awt.Color
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `/podcasts/{id}/artwork` (ADR 0013): the feed's image, square and small, kept by the server. */
class ArtworkTest {

    @BeforeTest
    fun reset() = TestDatabase.reset()

    private fun artwork(podcastId: Int, url: String?) =
        TestDatabase.execute("UPDATE podcasts SET artwork_url = ${url?.let { "'$it'" } ?: "NULL"} WHERE id = $podcastId")

    @Test
    fun theImageIsCutSquareScaledDownAndKept() = testApplication {
        podcastServer()
        val token = client.token()
        artwork(1, "https://cdn.test/artwork.png")
        val fetched = testArtworkRequests.get()

        val response = client.get("/podcasts/1/artwork") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Image.JPEG, response.contentType())
        assertEquals("private, max-age=86400", response.headers[HttpHeaders.CacheControl])
        val bytes = response.readRawBytes()
        val image = ImageIO.read(ByteArrayInputStream(bytes))
        assertEquals(256 to 256, image.width to image.height)
        // The centered square of the 600 × 400 image is blue: the red bands were cut off.
        listOf(2 to 2, 253 to 2, 2 to 253, 253 to 253, 128 to 128).forEach { (x, y) ->
            val color = Color(image.getRGB(x, y))
            assertTrue(color.blue > 200 && color.red < 60, "($x, $y) is $color")
        }

        // Kept: served again without asking the image's host, to every user.
        val again = client.get("/podcasts/1/artwork") { bearerAuth(client.token(TestSeed.BOB, TestSeed.BOB_PASSWORD)) }
        assertContentEquals(bytes, again.readRawBytes())
        assertEquals(fetched + 1, testArtworkRequests.get())
    }

    @Test
    fun withoutAReadableImageTheInitialsOnTheColorOfThePodcast() = testApplication {
        podcastServer()
        val token = client.token()
        artwork(1, "https://cdn.test/not-an-image.png")

        val response = client.get("/podcasts/1/artwork") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Image.SVG, response.contentType()?.withoutParameters())
        assertEquals("private, max-age=600", response.headers[HttpHeaders.CacheControl])
        assertTrue(">LC</text>" in response.bodyAsText()) // Les Cast Codeurs

        // Nor when the feed has no image at all.
        artwork(2, null)
        assertTrue(">TK</text>" in client.get("/podcasts/2/artwork") { bearerAuth(token) }.bodyAsText()) // Talking Kotlin
    }

    @Test
    fun forSignedInUsersAndKnownPodcastsOnly() = testApplication {
        podcastServer()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/podcasts/1/artwork").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/podcasts/999/artwork") { bearerAuth(client.token()) }.status)
    }
}
