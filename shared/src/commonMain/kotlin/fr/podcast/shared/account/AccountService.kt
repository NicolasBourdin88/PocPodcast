package fr.podcast.shared.account

import fr.podcast.shared.api.ApiException
import fr.podcast.shared.api.PodcastApi
import fr.podcast.shared.api.halResource
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonPrimitive

/**
 * Signs in and out by following the server's links, as any hypermedia client:
 * the root tells how to get a token (template `create-token`), `me` how to revoke it.
 */
class AccountService(private val httpClient: HttpClient, private val deviceName: String) {

    /** @throws ApiException Unreachable, NotAPodcastServer or Unauthorized (wrong credentials). */
    suspend fun signIn(serverUrl: String, username: String, password: String): Account {
        val url = normalizeServerUrl(serverUrl)
        val anonymous = PodcastApi(url, httpClient)
        val root = try {
            anonymous.get("/")
        } catch (unexpected: ApiException.Unexpected) {
            throw ApiException.NotAPodcastServer(url)
        } catch (notFound: ApiException.NotFound) {
            throw ApiException.NotAPodcastServer(url)
        }
        val createToken = root.template("create-token")
        if (root.int("apiVersion") == null || createToken == null) throw ApiException.NotAPodcastServer(url)

        val created = anonymous.submit(
            createToken, "/",
            mapOf("username" to JsonPrimitive(username), "password" to JsonPrimitive(password), "deviceName" to JsonPrimitive(deviceName)),
        ).halResource(url)
        val token = created.string("token") ?: throw ApiException.NotAPodcastServer(url)

        val api = PodcastApi(url, httpClient, token)
        val me = api.get(created.link("me") ?: "/me")
        return Account(
            serverUrl = url,
            serverName = root.string("name") ?: url,
            username = username,
            displayName = me.string("displayName") ?: username,
            avatar = me.string("avatar"),
            token = token,
        )
    }

    /** Revokes the device token when the server is reachable; the caller forgets the account anyway. */
    suspend fun signOut(account: Account) {
        try {
            val api = PodcastApi(account.serverUrl, httpClient, account.token)
            val meHref = api.get("/").link("me") ?: return
            val me = api.get(meHref)
            me.template("logout")?.let { api.submit(it, me.link("self") ?: meHref) }
        } catch (alreadyGone: ApiException) {
            // Unreachable server or token already revoked: nothing more to do.
        }
    }
}

/** "localhost:8080/" → "http://localhost:8080". HTTPS must be typed explicitly. */
fun normalizeServerUrl(input: String): String {
    val trimmed = input.trim().trimEnd('/')
    return if ("://" in trimmed) trimmed else "http://$trimmed"
}
