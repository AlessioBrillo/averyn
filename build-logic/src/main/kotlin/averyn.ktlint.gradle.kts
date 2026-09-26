plugins {
    id("org.jlleitschuh.gradle.ktlint")
}

// `./gradlew check` runs ktlintCheck; `./gradlew ktlintFormat` fixes. Style comes from .editorconfig.
ktlint {
    android.set(false)
}
