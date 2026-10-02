package dev.averyn.backend

import dev.averyn.backend.auth.OidcConfig
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
data class ClientConfig(
    val issuer: String,
    val clientId: String,
)

/** Public: lets a mobile app sign in knowing only the server URL (self-hosted servers have their own IdP). */
fun Application.clientConfig(oidc: OidcConfig) {
    routing {
        get("/v1/client-config") { call.respond(ClientConfig(oidc.issuer, oidc.clientId)) }
    }
}
