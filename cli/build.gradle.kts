plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeCompiler)
    application
}

application {
    applicationName = "podcast"
    mainClass.set("fr.podcast.cli.MainKt")
}

dependencies {
    implementation(projects.shared)
    implementation(libs.clikt)
    implementation(libs.mosaic.runtime)
    // Ktor logs through SLF4J: no output in a terminal tool.
    runtimeOnly(libs.slf4j.nop)

    testImplementation(libs.kotlin.test)
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
