package fr.podcast.server

import fr.podcast.server.opml.Opml
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class OpmlTest {

    @BeforeTest
    fun reset() = TestDatabase.reset()

    private val opml = ContentType("text", "x-opml")
    private val fixture get() = javaClass.getResourceAsStream("/feeds/import.opml")!!.readBytes()

    @Test
    fun subscriptionsAreExportedAsOpml() = testApplication {
        podcastServer()

        val response = client.get("/subscriptions/opml") { bearerAuth(client.token()) }

        assertEquals(opml, response.contentType()!!.withoutParameters())
        assertContains(response.headers[HttpHeaders.ContentDisposition]!!, "podcasts.opml")
        val entries = Opml.read(response.bodyAsText().byteInputStream())
        assertEquals(
            listOf("https://feeds.test/frag.xml", "https://feeds.test/cac.xml", "https://feeds.test/lcc.xml", "https://feeds.test/tk.xml"),
            entries.map { it.feedUrl },
        )
        assertEquals("Fragmented", entries.first().title)
    }

    @Test
    fun importSubscribesAndExplainsRefusals() = testApplication {
        podcastServer()
        val bob = client.token(TestSeed.BOB, TestSeed.BOB_PASSWORD)

        val report = client.post("/subscriptions/opml") {
            bearerAuth(bob)
            contentType(opml)
            setBody(fixture)
        }.json()

        // Talking Kotlin was already followed; the test feed and the address without scheme are new.
        assertEquals(2, report["imported"]!!.jsonPrimitive.int)
        assertEquals(1, report["alreadySubscribed"]!!.jsonPrimitive.int)
        val failed = report["failed"]!!.jsonArray.single().jsonObject
        assertEquals("ftp://feeds.test/old.xml", failed["feedUrl"]!!.jsonPrimitive.content)
        assertEquals("invalid-feed-url", failed["reason"]!!.jsonPrimitive.content)

        val subscriptions = client.getJson("/subscriptions", bob)["_embedded"]!!.jsonObject["subscriptions"]!!.jsonArray
            .associate { subscription ->
                val podcast = subscription.jsonObject["_embedded"]!!.jsonObject["podcast"]!!.jsonObject
                podcast["title"]!!.jsonPrimitive.content to podcast
            }
        assertEquals(setOf("Fragmented", "Le Podcast de Test", "Sans schéma", "Talking Kotlin"), subscriptions.keys)
        // Not read yet: the background job reads new feeds; here we ask for it.
        val pending = subscriptions.getValue("Le Podcast de Test")
        assertEquals("PENDING", pending["refreshStatus"]!!.jsonPrimitive.content)
        val read = client.post("/podcasts/${pending["id"]!!.jsonPrimitive.content}/refresh") { bearerAuth(bob) }.json()
        assertEquals("OK", read["refreshStatus"]!!.jsonPrimitive.content)
        val unknown = subscriptions.getValue("Sans schéma")["id"]!!.jsonPrimitive.content
        assertEquals("FAILED", client.post("/podcasts/$unknown/refresh") { bearerAuth(bob) }.json()["refreshStatus"]!!.jsonPrimitive.content)
    }

    @Test
    fun importFromTheThinClientForm() = testApplication {
        podcastServer()
        val token = client.token()

        val response = client.submitFormWithBinaryData(
            "/subscriptions/opml",
            formData {
                append("opml", fixture, Headers.build {
                    append(HttpHeaders.ContentType, "text/x-opml")
                    append(HttpHeaders.ContentDisposition, "filename=\"antennapod.opml\"")
                })
            },
        ) { bearerAuth(token) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(2, response.json()["imported"]!!.jsonPrimitive.int) // Alice already follows Talking Kotlin
    }

    @Test
    fun otherDocumentsAreRefused() = testApplication {
        podcastServer()
        val token = client.token()

        for (body in listOf("<rss version=\"2.0\"/>", "<html><body>Hello</body></html>", "not xml at all", "")) {
            val response = client.post("/subscriptions/opml") {
                bearerAuth(token)
                accept(HalForms)
                contentType(opml)
                setBody(body)
            }
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status, body)
            assertEquals("/problems/not-an-opml", response.json()["type"]!!.jsonPrimitive.content)
        }
    }
}
