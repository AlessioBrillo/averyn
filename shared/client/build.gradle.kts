plugins {
    id("averyn.kmp-library")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // api: the client speaks in the DTOs of shared:api, and in the analysis of shared:analysis.
            api(project(":shared:api"))
            api(project(":shared:analysis"))
            // api: the local store and the sync status of an activity are part of what the repository reads.
            api(project(":shared:tracking"))
            api(project(":shared:sync"))
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        // The engine is picked up from the classpath by HttpClient(): OkHttp on Android, Darwin on iOS.
        androidMain.dependencies { implementation(libs.ktor.client.okhttp) }
        iosMain.dependencies { implementation(libs.ktor.client.darwin) }
        jvmTest.dependencies { implementation(libs.ktor.client.mock) }
    }
}
