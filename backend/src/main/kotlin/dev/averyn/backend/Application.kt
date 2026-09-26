package dev.averyn.backend

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation

/** Entry point, referenced from application.yaml. Domain packages (activities, auth, …) are added as they are built. */
fun Application.module() {
    val cfg = environment.config
    val db =
        connectAndMigrate(
            url = cfg.property("averyn.database.url").getString(),
            user = cfg.property("averyn.database.user").getString(),
            password = cfg.property("averyn.database.password").getString(),
        )
    content()
    observability(isReady = db::isReachable)
}

fun Application.content() {
    install(ContentNegotiation) { json() }
}
