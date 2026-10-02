package dev.averyn.backend

import dev.averyn.backend.activities.activities
import dev.averyn.backend.auth.OidcConfig
import dev.averyn.backend.auth.auth
import dev.averyn.backend.storage.RawObjectStore
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation

/** Entry point, referenced from application.yaml. Domain packages (auth, activities, …) are added as they are built. */
fun Application.module() {
    val cfg = environment.config
    val db =
        connectAndMigrate(
            url = cfg.property("averyn.database.url").getString(),
            user = cfg.property("averyn.database.user").getString(),
            password = cfg.property("averyn.database.password").getString(),
        )
    val oidc =
        OidcConfig(
            issuer = cfg.property("averyn.oidc.issuer").getString(),
            audience = cfg.property("averyn.oidc.audience").getString(),
            clientId = cfg.property("averyn.oidc.clientId").getString(),
        )
    val raw =
        RawObjectStore(
            endpoint = cfg.property("averyn.s3.endpoint").getString(),
            bucket = cfg.property("averyn.s3.bucket").getString(),
            accessKey = cfg.property("averyn.s3.accessKey").getString(),
            secretKey = cfg.property("averyn.s3.secretKey").getString(),
        ).also { it.ensureBucket() }
    content()
    observability(isReady = db::isReachable)
    auth(oidc)
    clientConfig(oidc)
    activities(db, raw)
}

fun Application.content() {
    install(ContentNegotiation) { json() }
}
