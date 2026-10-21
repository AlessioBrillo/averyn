// Plugins are declared (not applied) here so every module resolves the same versions.
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
}

// The project needs JDK 21: the CI, the images and the documentation all say so. On an older JDK the tests of
// the backend hang instead of failing, and a minute of silence is a bad way to learn that JAVA_HOME points to
// the wrong JDK. The check is here, at the top of the build, so it costs a second instead of a build.
if (JavaVersion.current() < JavaVersion.VERSION_21) {
    throw GradleException(
        "Averyn needs JDK 21 and this build runs on ${JavaVersion.current()}. " +
            "Point JAVA_HOME at a JDK 21 (the CI and the images of the project use temurin 21).",
    )
}
