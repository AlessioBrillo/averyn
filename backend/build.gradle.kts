plugins {
    id("averyn.ktlint")
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin { jvmToolchain(21) }

application {
    mainClass.set("io.ktor.server.netty.EngineMain")
}

dependencies {
    implementation(project(":shared:tracking")) // the same replay/metrics code as the devices (TDD-0002 §5.4)
    implementation(project(":shared:analysis")) // the splits, the elevation and the series (TDD-0009)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.config.yaml)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.rate.limit)
    implementation(libs.ktor.server.call.id)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.metrics.micrometer)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.micrometer.registry.prometheus)

    implementation(libs.hikari)
    implementation(libs.postgresql)
    implementation(libs.awssdk.s3)
    implementation(libs.awssdk.url.connection.client)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)

    runtimeOnly(libs.logback.classic)
    runtimeOnly(libs.logstash.logback.encoder)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.postgresql)
}

tasks.test { useJUnitPlatform() }

// The backfill of the analysis (TDD-0009): a command the operator runs, never a job of the server.
tasks.register<JavaExec>("backfillAnalysis") {
    group = "application"
    description = "Computes the analysis of the activities that have none (TDD-0009)."
    mainClass.set("dev.averyn.backend.analysis.BackfillAnalysisKt")
    classpath = sourceSets["main"].runtimeClasspath
}
