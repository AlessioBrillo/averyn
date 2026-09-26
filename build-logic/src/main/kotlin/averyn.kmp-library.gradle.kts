// Convention for every shared/ module: pure Kotlin, targets jvm (backend + fast tests), android, iOS.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("averyn.ktlint")
}

kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()

    // namespace is set per module.
    android {
        compileSdk = 37
        minSdk = 26
    }

    sourceSets {
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
