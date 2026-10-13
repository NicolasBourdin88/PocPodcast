plugins {
    alias(libs.plugins.kotlinJvm)
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test)
}

tasks.test {
    // The comparison report written by the tests ends up here.
    systemProperty("poc.report", layout.buildDirectory.file("poc-report.md").get().asFile.path)
    testLogging { showStandardStreams = true }
}
