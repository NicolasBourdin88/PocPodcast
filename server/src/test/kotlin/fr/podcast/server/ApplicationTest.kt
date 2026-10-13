package fr.podcast.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplicationTest {

    @Test
    fun browserGetsTheHtmlHomePage() = testApplication {
        application { module() }

        val response = client.get("/") {
            header(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.contentType()!!.match(ContentType.Text.Html))
        val html = response.bodyAsText()
        assertContains(html, "Reprendre l'écoute")
        assertContains(html, "LCC 330 - Les nouveautés de Kotlin 2.4")
        assertContains(html, "hx-get=\"/fragments/latest\"")
    }

    @Test
    fun apiClientGetsTheSameHomeInHal() = testApplication {
        application { module() }

        val response = client.get("/") { header(HttpHeaders.Accept, "application/hal+json") }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("application/hal+json", response.contentType()!!.withoutParameters().toString())
        val home = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("/podcasts", home["_links"]!!.jsonObject["podcasts"]!!.jsonObject["href"]!!.jsonPrimitive.content)

        val resume = home["_embedded"]!!.jsonObject["resume"]!!.jsonArray
        val first = resume.first().jsonObject
        assertEquals("LCC 330 - Les nouveautés de Kotlin 2.4", first["title"]!!.jsonPrimitive.content)
        assertEquals("/episodes/e2/audio", first["_links"]!!.jsonObject["play"]!!.jsonObject["href"]!!.jsonPrimitive.content)
    }

    @Test
    fun htmxFragmentIsPartialHtml() = testApplication {
        application { module() }

        val html = client.get("/fragments/latest").bodyAsText()

        assertTrue(html.trimStart().startsWith("<ul"), html)
        assertContains(html, "id=\"latest\"")
    }
}
