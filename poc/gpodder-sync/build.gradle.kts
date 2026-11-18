plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    application
}

application {
    mainClass.set("poc.gpodder.ServerKt")
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.logback)
}
