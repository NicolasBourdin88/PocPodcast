import co.touchlab.skie.configuration.DefaultArgumentInterop

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.skie)
}

// The Kotlin SDK of the SwiftUI client (iOS and macOS, ADR 0013): one framework, PodcastKit, with
// :shared and :player. SKIE turns suspend functions into async ones, flows into async sequences
// and sealed classes into Swift enums.
kotlin {
    listOf(iosArm64(), iosSimulatorArm64(), macosArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "PodcastKit"
            isStatic = true
            export(projects.shared)
            export(projects.player)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.shared)
            api(projects.player)
        }
    }
}

skie {
    features {
        group {
            // Swift calls Kotlin functions without repeating their default values.
            DefaultArgumentInterop.Enabled(true)
        }
    }
}
