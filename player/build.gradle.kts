import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

// OpenJFX publishes one jar per platform, natives inside (ADR 0008).
val javafxPlatform = run {
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }
    when {
        "mac" in os -> if (arm) "mac-aarch64" else "mac"
        "win" in os -> "win"
        else -> if (arm) "linux-aarch64" else "linux"
    }
}

kotlin {
    // Desktop app and CLI.
    jvm()

    android {
        namespace = "fr.podcast.player"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(projects.shared)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmMain.dependencies {
            for (module in listOf("javafx-base", "javafx-graphics", "javafx-media")) {
                implementation("org.openjfx:$module:${libs.versions.openjfx.get()}:$javafxPlatform")
            }
        }
        androidMain.dependencies {
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.session)
        }
    }
}
