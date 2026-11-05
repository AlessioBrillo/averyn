// Convention for every shared/ module: pure Kotlin, targets jvm (backend + fast tests), android, iOS.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("averyn.ktlint")
}

// The backend Docker image has no Android SDK (AVERYN_BACKEND_ONLY, see settings.gradle.kts): the backend only
// consumes the jvm target, so the Android library plugin is applied everywhere else.
if (System.getenv("AVERYN_BACKEND_ONLY") == null) apply(plugin = "averyn.kmp-android")

kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }

// The message of a failed test, not only its class: a Kotlin/Native failure is otherwise a bare exception name.
tasks.withType<org.gradle.api.tasks.testing.AbstractTestTask>().configureEach {
    testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
}
