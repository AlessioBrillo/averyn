plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("averyn.ktlint")
}

// Tester builds (ADR-0019, TDD-0006 §5.8): the release workflow passes these; a developer build keeps the defaults.
val buildVersionCode = providers.gradleProperty("averyn.versionCode").map(String::toInt).getOrElse(1)
val buildVersionName = providers.gradleProperty("averyn.versionName").getOrElse("0.0.1")
val buildCommit = providers.gradleProperty("averyn.commit").getOrElse("dev")

// Release signing exists only when the environment provides a keystore (CI secret); never a file in the repo.
val releaseKeystore = providers.environmentVariable("AVERYN_KEYSTORE_FILE").orNull

android {
    namespace = "dev.averyn.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.averyn.app" // codename-based; must be final before any store upload (ADR-0008)
        minSdk = 26
        targetSdk = 37
        versionCode = buildVersionCode
        versionName = buildVersionName
        // Shown in the diagnostics report, so a device-matrix row and a store crash report can be matched.
        buildConfigField("String", "COMMIT_ID", "\"$buildCommit\"")
        // AppAuth's redirect receiver: must equal the scheme of the redirect URI registered with the IdP (idp-init.sh).
        manifestPlaceholders["appAuthRedirectScheme"] = "dev.averyn.app"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    if (releaseKeystore != null) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("AVERYN_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("AVERYN_KEY_ALIAS")
                keyPassword = System.getenv("AVERYN_KEY_PASSWORD")
            }
        }
        buildTypes { release { signingConfig = signingConfigs.getByName("release") } }
    }
}

dependencies {
    implementation(project(":shared:domain"))
    implementation(project(":shared:tracking"))
    implementation(project(":shared:metrics"))
    implementation(project(":shared:sensors"))
    implementation(project(":shared:sync"))
    implementation(project(":shared:api"))
    implementation(project(":shared:client"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime)
    implementation(libs.appauth)
    implementation(libs.maplibre.android)
    implementation(libs.androidx.health.connect)

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:${libs.versions.kotlin.get()}")
}
