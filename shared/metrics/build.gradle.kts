plugins {
    id("averyn.kmp-library")
}

kotlin {
    android { namespace = "dev.averyn.shared.metrics" }

    sourceSets {
        commonMain.dependencies { api(project(":shared:domain")) }
    }
}
