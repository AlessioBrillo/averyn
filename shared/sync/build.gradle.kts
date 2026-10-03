plugins {
    id("averyn.kmp-library")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // api: ActivitySync's public API takes an ActivityStore (and so exposes kotlinx-io's Path).
            api(project(":shared:tracking"))
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
