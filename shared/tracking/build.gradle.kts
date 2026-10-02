plugins {
    id("averyn.kmp-library")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":shared:domain"))
            api(project(":shared:metrics"))
            // api: ActivityStore's constructor takes a kotlinx.io Path, so it's part of this module's public API.
            api(libs.kotlinx.io.core)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

// FixtureConformanceTest reads tests/gps-fixtures/ (repo root) against the fixtures README's format.
tasks.withType<Test>().configureEach {
    systemProperty("averyn.fixturesDir", rootProject.file("tests/gps-fixtures").absolutePath)
}
