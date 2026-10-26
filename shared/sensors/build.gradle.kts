plugins {
    id("averyn.kmp-library")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.core)
        }
        // The golden vectors of the Synapse contract are a JSON file the JVM tests read.
        jvmTest.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
