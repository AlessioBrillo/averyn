// Swift can only consume one Kotlin framework without duplicating the runtime, so the
// iOS app links this single "Shared" framework that re-exports every shared module.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
            export(project(":shared:domain"))
            export(project(":shared:tracking"))
            export(project(":shared:metrics"))
            export(project(":shared:sensors"))
            export(project(":shared:sync"))
            export(project(":shared:analysis"))
            // The contract of the endpoints and the client that reads it (TDD-0011).
            export(project(":shared:api"))
            export(project(":shared:client"))
            // shared:tracking's public API (ActivityStore's constructor, writeGpx) exposes kotlinx-io
            // types (Path, Sink): exporting a module that exposes a dependency's types requires exporting
            // that dependency too, or Kotlin/Native's framework build fails ("exposes non-exported type").
            export(libs.kotlinx.io.core)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":shared:domain"))
            api(project(":shared:tracking"))
            api(project(":shared:metrics"))
            api(project(":shared:sensors"))
            api(project(":shared:sync"))
            api(project(":shared:analysis"))
            api(project(":shared:api"))
            api(project(":shared:client"))
            api(libs.kotlinx.io.core)
        }
    }
}
