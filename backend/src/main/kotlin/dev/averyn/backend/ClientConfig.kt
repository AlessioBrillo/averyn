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
    val webClientId: String,
    val mapStyleUrl: String,
)

/** Public: lets an app sign in knowing only the server URL (self-hosted servers have their own IdP and basemap). */
fun Application.clientConfig(
    oidc: OidcConfig,
    mapStyleUrl: String,
) {
    val config = ClientConfig(oidc.issuer, oidc.clientId, oidc.webClientId, mapStyleUrl)
    routing {
        get("/v1/client-config") { call.respond(config) }
    }
}
