package poc.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.system.measureTimeMillis
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackStoreTest {

    private lateinit var postgres: EmbeddedPostgres
    private lateinit var dataSource: HikariDataSource

    @BeforeTest
    fun setUp() {
        postgres = EmbeddedPostgres.builder().start()
        dataSource = HikariDataSource(HikariConfig().apply {
            jdbcUrl = postgres.getJdbcUrl("postgres", "postgres")
            username = "postgres"
        })
        // Same migration files as SQLDelight's schema.
        Flyway.configure()
            .dataSource(dataSource)
            .locations("filesystem:src/main/sqldelight/migrations")
            .sqlMigrationSuffixes(".sqm")
            .load()
            .migrate()
        seed()
    }

    @AfterTest
    fun tearDown() {
        dataSource.close()
        postgres.close()
    }

    private fun seed() = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeUpdate("INSERT INTO users(name) VALUES ('alice'), ('bob')")
            statement.executeUpdate(
                "INSERT INTO podcasts(title, feed_url) VALUES ('Talking Kotlin', 'https://a'), ('Les Cast Codeurs', 'https://b')",
            )
            statement.executeUpdate(
                """
                INSERT INTO episodes(podcast_id, title, published_on, duration_seconds) VALUES
                  (1, 'Compose Multiplatform for iOS is stable', '2026-10-12', 3120),
                  (2, 'LCC 330', '2026-10-10', 5700),
                  (1, 'Ktor 3', '2026-09-28', 3660)
                """.trimIndent(),
            )
        }
    }

    private fun stores(): Map<String, PlaybackStore> = mapOf(
        "Exposed" to ExposedPlaybackStore(Database.connect(dataSource)),
        "SQLDelight" to SqlDelightPlaybackStore(dataSource),
    )

    @Test
    fun bothLibrariesGiveTheSameResults() {
        for ((name, store) in stores()) {
            dataSource.connection.use { it.createStatement().executeUpdate("TRUNCATE playback_states") }

            store.savePosition(userId = 1, episodeId = 2, positionSeconds = 100, status = "IN_PROGRESS")
            store.savePosition(userId = 1, episodeId = 2, positionSeconds = 2280, status = "IN_PROGRESS") // upsert
            store.savePosition(userId = 1, episodeId = 3, positionSeconds = 3660, status = "PLAYED")
            store.savePosition(userId = 2, episodeId = 1, positionSeconds = 60, status = "IN_PROGRESS") // other user

            assertEquals(
                listOf(EpisodeRow("LCC 330", "Les Cast Codeurs", 5700, 2280, "IN_PROGRESS")),
                store.inProgress(userId = 1),
                name,
            )
            assertEquals(
                listOf("NEW", "IN_PROGRESS", "PLAYED"),
                store.latestWithState(userId = 1, limit = 10).map { it.status },
                name,
            )
            // Shared files, per-user state: bob sees his own progress on episode 1 only.
            assertEquals(listOf("IN_PROGRESS", "NEW", "NEW"), store.latestWithState(userId = 2, limit = 10).map { it.status }, name)
        }
    }

    @Test
    fun timings() {
        for ((name, store) in stores()) {
            store.latestWithState(1, 10) // warm-up
            val upserts = measureTimeMillis { repeat(1_000) { store.savePosition(1, 2, it, "IN_PROGRESS") } }
            val reads = measureTimeMillis { repeat(1_000) { store.latestWithState(1, 10) } }
            println("| $name | $upserts ms | $reads ms |")
        }
    }
}
