plugins {
    id("averyn.kmp-library")
}

kotlin {
    android { namespace = "dev.averyn.shared.tracking" }

    sourceSets {
        commonMain.dependencies {
            api(project(":shared:domain"))
            api(libs.kotlinx.coroutines.core)
        }
    }
}
