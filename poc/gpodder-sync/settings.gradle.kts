rootProject.name = "poc-gpodder-sync"

dependencyResolutionManagement {
    repositories { mavenCentral() }
    versionCatalogs {
        create("libs") { from(files("../../gradle/libs.versions.toml")) }
    }
}

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
