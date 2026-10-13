plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ktor)
    alias(libs.plugins.sqldelight)
    application
}

group = "fr.podcast"
version = "0.1.0"

application {
    // Production entry point: needs DATABASE_URL. `./gradlew :server:run` uses the dev one below.
    mainClass.set("fr.podcast.server.ApplicationKt")
}

sqldelight {
    databases {
        create("PodcastDb") {
            packageName.set("fr.podcast.server.db")
            dialect(libs.sqldelight.postgresql.dialect)
            // The Flyway migrations are the schema: every query is checked against them at build time (ADR 0004).
            srcDirs("src/main/sqldelight")
            deriveSchemaFromMigrations.set(true)
            verifyMigrations.set(false)
        }
    }
}

// Development only: embedded PostgreSQL and demo data, kept out of the production distribution.
val dev by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    runtimeClasspath += sourceSets.main.get().output + sourceSets.main.get().runtimeClasspath
}
kotlin.target.compilations.named("dev") {
    associateWith(kotlin.target.compilations.getByName("main"))
}
sourceSets.test {
    compileClasspath += dev.output
    runtimeClasspath += dev.output
}

dependencies {
    implementation(projects.shared)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.html.builder)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.sessions)
    implementation(libs.ktor.server.swagger)
    implementation(libs.logback)

    implementation(libs.sqldelight.jdbc.driver)
    implementation(libs.postgresql)
    implementation(libs.hikari)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.password4j)

    "devImplementation"(libs.embedded.postgres)
    "devImplementation"(platform(libs.embedded.postgres.binaries.bom))

    testImplementation(libs.embedded.postgres)
    testImplementation(platform(libs.embedded.postgres.binaries.bom))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.json.schema.validator)
}

// Flyway reads the same migration files from the classpath.
tasks.processResources {
    from("src/main/sqldelight/migrations") { into("db/migration") }
}

tasks.named<JavaExec>("run") {
    mainClass.set("fr.podcast.server.dev.DevServerKt")
    classpath = dev.runtimeClasspath
}
