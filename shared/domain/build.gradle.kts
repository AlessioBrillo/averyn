plugins {
    id("averyn.kmp-library")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android { namespace = "dev.averyn.shared.domain" }

    sourceSets {
        // api: domain types are @Serializable, so anything depending on domain can (de)serialize them too.
        commonMain.dependencies { api(libs.kotlinx.serialization.core) }
    }
}
