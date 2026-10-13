package fr.podcast.server.dev

import fr.podcast.server.ServerConfig
import fr.podcast.server.db.ServerDatabase
import fr.podcast.server.startServer
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * `./gradlew :server:run`: starts the server with an embedded PostgreSQL (no Docker needed)
 * kept in `server/build/dev-postgres-<port>`, and demo data on first start (see [DevSeed]).
 *
 * With DATABASE_URL set, uses that database instead. PORT and SERVER_NAME allow running a
 * second server (e.g. `PORT=8081 SERVER_NAME="Serveur B"`) to try multi-server clients.
 */
fun main() {
    val config = ServerConfig.fromEnvironment(defaultName = "Podcast (dev)", defaultSecureCookies = false)
    val database = System.getenv("DATABASE_URL")
        ?.let { ServerDatabase.connect(it, System.getenv("DATABASE_USER") ?: "podcast", System.getenv("DATABASE_PASSWORD")) }
        ?: embeddedDatabase(config.port)

    runBlocking { DevSeed.seedIfEmpty(database) }
    println("Serveur « ${config.name} » : http://localhost:${config.port} — API : http://localhost:${config.port}/docs")
    println("Comptes de démo : voir server/src/dev/kotlin/fr/podcast/server/dev/DevSeed.kt")
    startServer(config, database)
}

private fun embeddedDatabase(port: Int): ServerDatabase {
    val postgres = EmbeddedPostgres.builder()
        .setDataDirectory(File("build/dev-postgres-$port"))
        .setCleanDataDirectory(false)
        .start()
    Runtime.getRuntime().addShutdownHook(Thread { postgres.close() })
    return ServerDatabase.connect(postgres.getJdbcUrl("postgres", "postgres"), "postgres", null)
}
