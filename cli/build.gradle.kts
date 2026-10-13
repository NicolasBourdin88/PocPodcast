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

    testImplementation(libs.kotlin.test)
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
