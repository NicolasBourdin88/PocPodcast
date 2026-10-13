plugins {
    alias(libs.plugins.kotlinJvm)
    id("app.cash.sqldelight") version "2.4.1"
}

dependencies {
    // Shared by both approaches
    implementation("org.postgresql:postgresql:42.7.14")
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("org.flywaydb:flyway-core:13.10.0")
    implementation("org.flywaydb:flyway-database-postgresql:13.10.0")

    // Option A: Exposed
    implementation("org.jetbrains.exposed:exposed-core:1.5.1")
    implementation("org.jetbrains.exposed:exposed-jdbc:1.5.1")
    implementation("org.jetbrains.exposed:exposed-java-time:1.5.1")

    // Option B: SQLDelight
    implementation("app.cash.sqldelight:jdbc-driver:2.4.1")

    // Real PostgreSQL without Docker
    testImplementation("io.zonky.test:embedded-postgres:2.2.2")
    testImplementation(platform("io.zonky.test.postgres:embedded-postgres-binaries-bom:18.6.0"))
    testImplementation(libs.kotlin.test)
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
}

sqldelight {
    databases {
        create("PocDatabase") {
            packageName.set("poc.persistence.sqldelight")
            dialect("app.cash.sqldelight:postgresql-dialect:2.4.1")
            // The Flyway migrations are the schema: SQLDelight checks every query against them at build time.
            srcDirs("src/main/sqldelight")
            deriveSchemaFromMigrations.set(true)
            verifyMigrations.set(false)
        }
    }
}

tasks.test {
    testLogging { showStandardStreams = true }
}
