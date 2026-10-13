package fr.podcast.server

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
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

class SearchTest {

    @BeforeTest
    fun reset() = TestDatabase.reset()

    @Test
    fun bothDirectoriesAreMergedWithoutDuplicates() = testApplication {
        podcastServer()
        val token = client.token()

        val response = client.getJson("/search?q=kotlin", token)

        assertEquals(listOf("apple", "podcastindex"), response["sources"]!!.jsonArray.map { it.jsonPrimitive.content })
        val results = response["_embedded"]!!.jsonObject["results"]!!.jsonArray.map { it.jsonObject }
        // Interleaved by rank; "Kotlin Internals" is in both (same feed, different spelling); no public feed is skipped.
        assertEquals(listOf("Talking Kotlin", "Le Podcast de Test", "Kotlin Internals"), results.map { it["title"]!!.jsonPrimitive.content })
        assertEquals(listOf("apple", "podcastindex", "apple"), results.map { it["source"]!!.jsonPrimitive.content })

        // Alice follows Talking Kotlin: no subscribe template for it.
        val talkingKotlin = results[0]
        assertTrue(talkingKotlin["subscribed"]!!.jsonPrimitive.boolean)
        assertNull(talkingKotlin["_templates"])
        val test = results[1]
        assertFalse(test["subscribed"]!!.jsonPrimitive.boolean)
        val subscribe = test["_templates"]!!.jsonObject["subscribe"]!!.jsonObject
        assertEquals("https://feeds.test/podcast20.xml", subscribe["properties"]!!.jsonArray.single().jsonObject["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun aBrokenDirectoryIsSkipped() = testApplication {
        podcastServer()
        val token = client.token()

        val partial = client.getJson("/search?q=demi-panne", token)
        assertEquals(listOf("apple"), partial["sources"]!!.jsonArray.map { it.jsonPrimitive.content })

        val broken = client.get("/search?q=panne") { bearerAuth(token) }
        assertEquals(HttpStatusCode.BadGateway, broken.status)
        assertEquals("/problems/search-failed", broken.json()["type"]!!.jsonPrimitive.content)

        assertEquals(HttpStatusCode.BadRequest, client.get("/search?q=k") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/search") { bearerAuth(token) }.status)
    }
}
