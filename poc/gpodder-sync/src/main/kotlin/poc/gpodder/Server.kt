package poc.gpodder

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

/**
 * Study 2 POC: a minimal gPodder API v2, in memory, that writes every request it receives to
 * `build/requests.log`, to watch what a real AntennaPod sends. Any password is accepted for the
 * user "demo": the point is the protocol, not the accounts. Never a model for the real server.
 *
 * `GET /poc/state` dumps the store; `POST /poc/play` adds a play action "from another device".
 */
private const val USER = "demo"

private val logFile = File("build/requests.log").apply { parentFile.mkdirs(); writeText("") }

private fun trace(line: String) {
    val stamped = "${LocalTime.now().truncatedTo(ChronoUnit.SECONDS)} $line"
    println(stamped)
    logFile.appendText(stamped + "\n")
}

private object Store {
    /** Device id → its description. */
    val devices = linkedMapOf<String, JsonObject>()

    /** Feed URL → subscribed or not, and when that last changed (seconds). */
    val subscriptions = linkedMapOf<String, Pair<Boolean, Long>>()

    /** Every action received, with the second it arrived. */
    val actions = mutableListOf<Pair<JsonObject, Long>>()
    val sessions = mutableSetOf<String>()
}

private fun now() = System.currentTimeMillis() / 1000

/** How the call authenticated: "cookie", "basic", or null. Basic must name the right user. */
private fun ApplicationCall.authenticatedBy(): String? {
    if (request.cookies["sessionid"] in Store.sessions) return "cookie"
    val basic = request.header("Authorization")?.removePrefix("Basic ")?.takeIf { it != request.header("Authorization") } ?: return null
    val user = String(Base64.getDecoder().decode(basic)).substringBefore(':')
    return "basic".takeIf { user == USER && parameters["username"]?.let { it == USER } != false }
}

private suspend fun ApplicationCall.body(): String = receiveText().also { if (it.isNotBlank()) trace("   body: ${it.take(3000)}") }

private suspend fun ApplicationCall.json(value: Any) = respondText(
    when (value) {
        is JsonObject -> value.toString()
        is JsonArray -> value.toString()
        else -> value.toString()
    },
    ContentType.Application.Json,
)

private suspend fun ApplicationCall.requireAuth(): Boolean {
    if (authenticatedBy() != null) return true
    respond(HttpStatusCode.Unauthorized)
    return false
}

fun main() {
    // A feed to find on the first synchronisation.
    Store.subscriptions["https://anchor.fm/s/fdcf2fa8/podcast/rss"] = true to now()

    embeddedServer(Netty, port = 8090, host = "0.0.0.0") {
        intercept(ApplicationCallPipeline.Monitoring) {
            val request = call.request
            val auth = request.header("Authorization")?.let { header ->
                if (header.startsWith("Basic ")) "Basic(${String(Base64.getDecoder().decode(header.removePrefix("Basic "))).substringBefore(':')})" else header.substringBefore(' ')
            } ?: "none"
            trace("→ ${request.httpMethod.value} ${request.uri}  auth=$auth  cookies=${request.cookies.rawCookies.keys}  ua=${request.header("User-Agent")}")
            proceed()
            trace("← ${call.response.status()?.value}  set-cookie=${call.response.headers.values("Set-Cookie").map { it.substringBefore('=') }}")
        }
        routing {
            post("/api/2/auth/{username}/login.json") {
                call.body()
                if (call.authenticatedBy() == null) return@post call.respond(HttpStatusCode.Unauthorized)
                val session = call.request.cookies["sessionid"]?.takeIf { it in Store.sessions } ?: UUID.randomUUID().toString().also { Store.sessions += it }
                call.response.headers.append("Set-Cookie", "sessionid=$session; Path=/; HttpOnly")
                call.respond(HttpStatusCode.OK)
            }
            post("/api/2/auth/{username}/logout.json") {
                call.request.cookies["sessionid"]?.let { Store.sessions -= it }
                call.respond(HttpStatusCode.OK)
            }
            get("/api/2/devices/{username}.json") {
                if (!call.requireAuth()) return@get
                val subscribed = Store.subscriptions.count { it.value.first }
                call.json(buildJsonArray {
                    Store.devices.values.forEach { device ->
                        add(buildJsonObject {
                            device.forEach { (key, value) -> put(key, value) }
                            put("subscriptions", subscribed)
                        })
                    }
                })
            }
            post("/api/2/devices/{username}/{device}.json") {
                if (!call.requireAuth()) return@post
                val id = call.parameters["device"]!!
                val sent = call.body().takeIf { it.isNotBlank() }?.let { Json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())
                Store.devices[id] = buildJsonObject {
                    put("id", id)
                    put("caption", sent["caption"]?.jsonPrimitive?.content ?: Store.devices[id]?.get("caption")?.jsonPrimitive?.content ?: "")
                    put("type", sent["type"]?.jsonPrimitive?.content ?: Store.devices[id]?.get("type")?.jsonPrimitive?.content ?: "other")
                }
                call.respond(HttpStatusCode.OK)
            }
            get("/api/2/subscriptions/{username}/{device}.json") {
                if (!call.requireAuth()) return@get
                val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0
                val changed = Store.subscriptions.filter { it.value.second >= since }
                call.json(buildJsonObject {
                    put("add", buildJsonArray { changed.filter { it.value.first }.keys.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                    put("remove", buildJsonArray { changed.filter { !it.value.first }.keys.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                    put("timestamp", now())
                })
            }
            post("/api/2/subscriptions/{username}/{device}.json") {
                if (!call.requireAuth()) return@post
                val changes = Json.parseToJsonElement(call.body()).jsonObject
                val add = changes["add"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                val remove = changes["remove"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                if (add.intersect(remove.toSet()).isNotEmpty()) return@post call.respond(HttpStatusCode.BadRequest)
                val at = now()
                add.forEach { Store.subscriptions[it] = true to at }
                remove.forEach { Store.subscriptions[it] = false to at }
                call.json(buildJsonObject {
                    put("timestamp", at)
                    put("update_urls", JsonArray(emptyList()))
                })
            }
            get("/api/2/episodes/{username}.json") {
                if (!call.requireAuth()) return@get
                val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0
                call.json(buildJsonObject {
                    put("actions", JsonArray(Store.actions.filter { it.second >= since }.map { it.first }))
                    put("timestamp", now())
                })
            }
            post("/api/2/episodes/{username}.json") {
                if (!call.requireAuth()) return@post
                val at = now()
                Json.parseToJsonElement(call.body()).jsonArray.forEach { Store.actions += it.jsonObject to at }
                call.json(buildJsonObject {
                    put("timestamp", at)
                    put("update_urls", JsonArray(emptyList()))
                })
            }

            // POC controls, without authentication.
            get("/poc/state") {
                call.json(buildJsonObject {
                    put("devices", JsonArray(Store.devices.values.toList()))
                    put("subscriptions", buildJsonObject { Store.subscriptions.forEach { (url, state) -> put(url, "${state.first} @ ${state.second}") } })
                    put("actions", JsonArray(Store.actions.map { it.first }))
                })
            }
            post("/poc/play") {
                val action = Json.parseToJsonElement(call.body()).jsonObject
                Store.actions += JsonObject(action + mapOf("action" to kotlinx.serialization.json.JsonPrimitive("play"))) to now()
                call.respond(HttpStatusCode.OK)
            }

            // Anything else AntennaPod might ask for: logged, then 404.
            route("{...}") {
                handle {
                    call.body()
                    call.respond(HttpStatusCode.NotFound)
                }
            }
        }
    }.start(wait = true)
}

