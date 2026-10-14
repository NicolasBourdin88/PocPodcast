plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

// OpenJFX publishes one jar per platform (natives inside).
val javafxPlatform = run {
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }
    when {
        "mac" in os -> if (arm) "mac-aarch64" else "mac"
        "win" in os -> "win"
        else -> if (arm) "linux-aarch64" else "linux"
    }
}

dependencies {
    for (module in listOf(libs.javafx.base, libs.javafx.graphics, libs.javafx.media)) {
        implementation(variantOf(module) { classifier(javafxPlatform) })
    }
}

application {
    mainClass.set("MainKt")
}

tasks.named<JavaExec>("run") {
    args(layout.buildDirectory.file("poc-report.md").get().asFile.path)
}
