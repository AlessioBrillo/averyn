plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("averyn.ktlint")
}

android {
    namespace = "dev.averyn.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.averyn.app" // codename-based; must be final before any store upload (ADR-0008)
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.0.1"
        // AppAuth's redirect receiver: must equal the scheme of the redirect URI registered with the IdP (idp-init.sh).
        manifestPlaceholders["appAuthRedirectScheme"] = "dev.averyn.app"
    }

    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":shared:domain"))
    implementation(project(":shared:tracking"))
    implementation(project(":shared:metrics"))
    implementation(project(":shared:sync"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime)
    implementation(libs.appauth)

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:${libs.versions.kotlin.get()}")
}
