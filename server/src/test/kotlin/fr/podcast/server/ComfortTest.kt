package fr.podcast.server

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.get
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Base64
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** Search, tags and listening statistics (ADR 0012). */
class ComfortTest {

    @BeforeTest
    fun reset() = TestDatabase.reset()

    private suspend fun HttpClient.ids(path: String, token: String): List<String> =
        getJson(path, token)["_embedded"]!!.jsonObject["episodes"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

    private suspend fun HttpClient.send(method: String, path: String, token: String, body: String? = null) = when (method) {
        "PUT" -> put(path) { bearerAuth(token); body?.let { contentType(ContentType.Application.Json); setBody(it) } }
        "PATCH" -> patch(path) { bearerAuth(token); body?.let { contentType(ContentType.Application.Json); setBody(it) } }
        else -> post(path) { bearerAuth(token); body?.let { contentType(ContentType.Application.Json); setBody(it) } }
    }

    @Test
    fun episodesAreSearchedWithEveryCriterion() = testApplication {
        podcastServer()
        val alice = client.token()

        // Every word, anywhere, in any case: "kotlin" is also the podcast "Talking Kotlin".
        assertEquals(listOf("1", "2", "5", "9"), client.ids("/episodes?q=KOTLIN", alice))
        assertEquals(listOf("2"), client.ids("/episodes?q=kotlin%20nouveaut%C3%A9s", alice))
        // Characters of regular expressions are only characters.
        assertEquals(emptyList(), client.ids("/episodes?q=.*", alice))
        assertEquals(listOf("2", "4"), client.ids("/episodes?status=IN_PROGRESS", alice))
        assertEquals(listOf("1", "9"), client.ids("/episodes?q=kotlin&status=NEW", alice))
        assertEquals(listOf("3", "4"), client.ids("/episodes?from=2026-10-01&to=2026-10-08", alice))
        assertEquals(listOf("2", "6"), client.ids("/episodes?podcast=1", alice))
        // An empty field of a form means "any".
        assertEquals(9, client.ids("/episodes?q=&status=&minRating=0&favorite=false", alice).size)

        client.send("PUT", "/episodes/7/favorite", alice)
        client.send("PUT", "/episodes/3/rating", alice, """{"rating":4}""")
        client.send("PUT", "/episodes/7/rating", alice, """{"rating":2}""")
        assertEquals(listOf("7"), client.ids("/episodes?favorite=true", alice))
        assertEquals(listOf("3"), client.ids("/episodes?minRating=3", alice))

        client.send("PATCH", "/subscriptions/2", alice, """{"tags":["tech"]}""")
        assertEquals(listOf("1", "5", "9"), client.ids("/episodes?tag=TECH", alice))

        // Pages keep the filters.
        val page = client.getJson("/episodes?q=kotlin&limit=2", alice)
        assertEquals(4, page["total"]!!.jsonPrimitive.int)
        assertEquals("/episodes?q=kotlin&offset=2&limit=2", page["_links"]!!.jsonObject["next"]!!.jsonObject["href"]!!.jsonPrimitive.content)

        assertEquals(HttpStatusCode.BadRequest, client.get("/episodes?minRating=9") { bearerAuth(alice) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/episodes?from=yesterday") { bearerAuth(alice) }.status)
    }

    @Test
    fun tagsBelongToTheSubscriptionAndFilterTheLibrary() = testApplication {
        podcastServer()
        val alice = client.token()

        val tagged = client.send("PATCH", "/subscriptions/2", alice, """{"tags":["tech"," Tech ","english"]}""").json()
        // Trimmed, merged when they only differ by case, in the order of their names.
        assertEquals(listOf("english", "tech"), tagged["tags"]!!.jsonArray.map { it.jsonPrimitive.content })
        // As a form sends them.
        val text = client.send("PATCH", "/subscriptions/1", alice, """{"tags":"tech, java"}""").json()
        assertEquals(listOf("java", "tech"), text["tags"]!!.jsonArray.map { it.jsonPrimitive.content })
        // A change of the note keeps them.
        client.send("PATCH", "/subscriptions/2", alice, """{"note":"JetBrains"}""")
        assertEquals(listOf("english", "tech"), client.getJson("/subscriptions/2", alice)["tags"]!!.jsonArray.map { it.jsonPrimitive.content })

        val tooMany = client.send("PATCH", "/subscriptions/2", alice, """{"tags":[${(1..11).joinToString { "\"t$it\"" }}]}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, tooMany.status)
        assertEquals(HttpStatusCode.UnprocessableEntity, client.send("PATCH", "/subscriptions/2", alice, """{"tags":["a,b"]}""").status)

        fun titles(collection: kotlinx.serialization.json.JsonObject) = collection["_embedded"]!!.jsonObject["subscriptions"]!!.jsonArray
            .map { it.jsonObject["_embedded"]!!.jsonObject["podcast"]!!.jsonObject["title"]!!.jsonPrimitive.content }
        assertEquals(listOf("Les Cast Codeurs", "Talking Kotlin"), titles(client.getJson("/subscriptions?tag=tech", alice)))
        assertEquals(listOf("Talking Kotlin"), titles(client.getJson("/subscriptions?q=jetbrains", alice)))
    }

    @Test
    fun listeningTimeAddsUpWhatWasHeard() = testApplication {
        podcastServer()
        val alice = client.token()

        // A listening session: 15 s, then 15 s more; a jump ahead and "played" add nothing more.
        client.send("PUT", "/episodes/7/progress", alice, """{"positionSeconds":15}""")
        client.send("PUT", "/episodes/7/progress", alice, """{"positionSeconds":30}""")
        client.send("PUT", "/episodes/7/progress", alice, """{"positionSeconds":1500}""")
        client.send("POST", "/episodes/7/played", alice)
        // A gPodder client tells what it heard: from 100 to 400 s.
        val basic = "Basic " + Base64.getEncoder().encodeToString("${TestSeed.ALICE}:${TestSeed.ALICE_PASSWORD}".toByteArray())
        val day = LocalDate.now(ZoneOffset.UTC)
        client.post("/api/2/episodes/alice.json") {
            headers.append("Authorization", basic)
            setBody("""[{"podcast":"https://feeds.test/frag.xml","episode":"https://cdn.test/podcast/seed-3.mp3","guid":"seed-3","action":"play","timestamp":"${day}T23:59:59","started":100,"position":400,"total":2820}]""")
        }

        val stats = client.getJson("/me/stats", alice)
        assertEquals(330, stats["totalSeconds"]!!.jsonPrimitive.long)
        assertEquals(330, stats["last7DaysSeconds"]!!.jsonPrimitive.long)
        assertEquals(1, stats["episodesFinished"]!!.jsonPrimitive.int)
        assertEquals(day.toString(), stats["since"]!!.jsonPrimitive.content)
        val fragmented = stats["byPodcast"]!!.jsonArray.single().jsonObject
        assertEquals("Fragmented", fragmented["podcastTitle"]!!.jsonPrimitive.content)
        assertEquals(330, fragmented["seconds"]!!.jsonPrimitive.long)
        val months = stats["byMonth"]!!.jsonArray.map { it.jsonObject }
        assertEquals(12, months.size)
        assertEquals(YearMonth.now(ZoneOffset.UTC).toString(), months.last()["month"]!!.jsonPrimitive.content)
        assertEquals(330, months.last()["seconds"]!!.jsonPrimitive.long)

        // Each user their own.
        assertEquals(0, client.getJson("/me/stats", client.token(TestSeed.BOB, TestSeed.BOB_PASSWORD))["totalSeconds"]!!.jsonPrimitive.long)
        assertContains(client.getJson("/me", alice)["_links"].toString(), "/me/stats")
    }
}
