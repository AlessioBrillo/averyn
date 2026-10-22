package dev.averyn.backend

import dev.averyn.api.Api
import dev.averyn.api.ClientConfig
import dev.averyn.api.SemanticVersion
import dev.averyn.backend.auth.OidcConfig
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory

/**
 * Public: lets an app sign in knowing only the server URL (self-hosted servers have their own IdP and basemap).
 *
 * It also tells the app which contract the server speaks (`apiVersion`) and which app version the server still
 * supports (`minClientVersion`, TDD-0011). Both are advisory: the app warns, it never refuses to work.
 */
fun Application.clientConfig(
    oidc: OidcConfig,
    mapStyleUrl: String,
    minClientVersion: String? = null,
) {
    // A minimum that is not a version is a configuration mistake: it is ignored (and logged), never fatal —
    // the server has to keep serving its clients.
    val minimum =
        minClientVersion?.let {
            if (SemanticVersion.parseOrNull(it) == null) {
                log.warn("ignoring the configured minimum client version, it is not a version: {}", it)
                null
            } else {
                it
            }
        }
    val config =
        ClientConfig(
            issuer = oidc.issuer,
            clientId = oidc.clientId,
            webClientId = oidc.webClientId,
            mapStyleUrl = mapStyleUrl,
            apiVersion = Api.API_VERSION,
            minClientVersion = minimum,
        )
    routing {
        get("/v1/client-config") { call.respond(config) }
    }
}

private val log = LoggerFactory.getLogger("dev.averyn.backend.ClientConfig")
