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
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":shared:domain"))
            api(project(":shared:tracking"))
            api(project(":shared:metrics"))
        }
    }
}
