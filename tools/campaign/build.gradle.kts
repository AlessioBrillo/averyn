plugins {
    id("averyn.ktlint")
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin { jvmToolchain(21) }

application {
    mainClass.set("dev.averyn.campaign.MainKt")
}

dependencies {
    implementation(project(":shared:tracking")) // the report as the apps write it: no second copy of its shape
    implementation(project(":backend")) // the FIT reader of the import (TDD-0013): the strap's own file is read by it

    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }

// The documented command runs from the repository root and the defaults (the GPX schema) are relative to it: Gradle's
// own default for run is the project directory, which made --gpx fail with exit 64.
tasks.named<JavaExec>("run") { workingDir = rootProject.projectDir }
