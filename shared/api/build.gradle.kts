plugins {
    id("averyn.kmp-library")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // api: the DTOs of this module are the contract; their types are part of the public API.
            api(libs.kotlinx.serialization.core)
            // api: Track carries the GeoJSON properties as a JsonObject.
            api(libs.kotlinx.serialization.json)
        }
    }
}
