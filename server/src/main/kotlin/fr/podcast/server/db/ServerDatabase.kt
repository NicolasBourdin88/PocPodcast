package fr.podcast.server.db

import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.sql.DataSource
import kotlin.time.Instant

/** PostgreSQL access: Flyway migrations, then SQLDelight queries (ADR 0004). */
class ServerDatabase(dataSource: DataSource) {

    private val db = PodcastDb(dataSource.asJdbcDriver())

    /** Runs blocking JDBC calls off the request threads. */
    suspend fun <T> query(block: PodcastDb.() -> T): T = withContext(Dispatchers.IO) { db.block() }

    /** Runs [block] in a single transaction. */
    suspend fun <T> transaction(block: PodcastDb.() -> T): T =
        withContext(Dispatchers.IO) { db.transactionWithResult { db.block() } }

    companion object {
        /** Applies the migrations (the same `.sqm` files SQLDelight checks the queries against). */
        fun migrate(dataSource: DataSource) {
            Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .sqlMigrationSuffixes(".sqm")
                .load()
                .migrate()
        }

        fun connect(jdbcUrl: String, user: String, password: String?): ServerDatabase {
            val dataSource = HikariDataSource(
                HikariConfig().apply {
                    this.jdbcUrl = jdbcUrl
                    username = user
                    this.password = password
                    maximumPoolSize = 10
                },
            )
            migrate(dataSource)
            return ServerDatabase(dataSource)
        }
    }
}

fun OffsetDateTime.toKotlinInstant(): Instant = Instant.fromEpochSeconds(toEpochSecond(), nano)

fun Instant.toOffsetDateTime(): OffsetDateTime =
    OffsetDateTime.ofInstant(java.time.Instant.ofEpochSecond(epochSeconds, nanosecondsOfSecond.toLong()), ZoneOffset.UTC)
