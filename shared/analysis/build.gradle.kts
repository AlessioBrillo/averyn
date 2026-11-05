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
            // api: the privacy zones cut a Track, which is a DTO of the contract.
            api(project(":shared:api"))
            api(libs.kotlinx.serialization.core)
        }
    }
}
