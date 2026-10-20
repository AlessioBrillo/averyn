plugins {
    id("averyn.kmp-library")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":shared:domain"))
            api(project(":shared:metrics"))
            api(project(":shared:sensors"))
            api(libs.kotlinx.serialization.core)
        }
    }
}
