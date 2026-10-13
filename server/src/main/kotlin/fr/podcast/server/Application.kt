package fr.podcast.server

import fr.podcast.server.api.apiRoutes
import fr.podcast.server.auth.Accounts
import fr.podcast.server.auth.installAuthentication
import fr.podcast.server.db.ServerDatabase
import fr.podcast.server.web.webRoutes
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.routing.routing
import kotlin.system.exitProcess

data class ServerConfig(
    /** Name shown to clients (root resource), useful when a client knows several servers. */
    val name: String,
    val port: Int = 8080,
    val host: String = "0.0.0.0",
    /** Cookies only sent over HTTPS: true in production. */
    val secureCookies: Boolean = true,
) {
    companion object {
        fun fromEnvironment(defaultName: String, defaultSecureCookies: Boolean) = ServerConfig(
            name = System.getenv("SERVER_NAME") ?: defaultName,
            port = System.getenv("PORT")?.toIntOrNull() ?: 8080,
            secureCookies = System.getenv("SECURE_COOKIES")?.toBooleanStrictOrNull() ?: defaultSecureCookies,
        )
    }
}

/** Production entry point: a real PostgreSQL is required. For development use `./gradlew :server:run`. */
fun main() {
    val url = System.getenv("DATABASE_URL")
    if (url == null) {
        System.err.println("DATABASE_URL manquant (ex. jdbc:postgresql://localhost:5432/podcast). En développement : ./gradlew :server:run")
        exitProcess(1)
    }
    val database = ServerDatabase.connect(url, System.getenv("DATABASE_USER") ?: "podcast", System.getenv("DATABASE_PASSWORD"))
    startServer(ServerConfig.fromEnvironment(defaultName = "Podcast", defaultSecureCookies = true), database)
}

fun startServer(config: ServerConfig, database: ServerDatabase) {
    embeddedServer(Netty, port = config.port, host = config.host) { module(config, database) }.start(wait = true)
}

fun Application.module(config: ServerConfig, database: ServerDatabase) {
    val accounts = Accounts(database)
    installAuthentication(accounts, database, config.secureCookies)
    // Every resource is negotiated on these two headers (docs/api/conventions.md).
    install(createApplicationPlugin("Vary") { onCall { it.response.headers.append(HttpHeaders.Vary, "Accept, Accept-Language") } })

    routing {
        apiRoutes(config, database, accounts)
        webRoutes(database, accounts)
        swaggerUI(path = "docs", swaggerFile = "openapi/podcast-api.yaml")
    }
}
