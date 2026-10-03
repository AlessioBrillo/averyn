plugins {
    id("averyn.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies { api(project(":shared:domain")) }
    }
}
