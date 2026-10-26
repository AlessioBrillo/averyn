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
    privacyPolicyUrl: String? = null,
    termsUrl: String? = null,
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
            privacyPolicyUrl = legalUrl("privacy policy", privacyPolicyUrl),
            termsUrl = legalUrl("terms", termsUrl),
        )
    routing {
        get("/v1/client-config") { call.respond(config) }
    }
}

/**
 * A link to a document of the operator (the privacy notice, the terms): an absolute `http` or `https` URL, or nothing.
 * A value that is anything else (a relative path, a `javascript:` URL) is a configuration mistake: it is ignored and
 * logged, because apps and the web open this link and the server has to keep serving its clients.
 */
internal fun Application.legalUrl(
    what: String,
    value: String?,
): String? {
    val text = value?.trim()?.ifEmpty { null } ?: return null
    val uri = runCatching { java.net.URI(text) }.getOrNull()
    val valid = uri != null && uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrEmpty()
    if (!valid) {
        log.warn("ignoring the configured {} link, it is not an http(s) URL", what)
        return null
    }
    return text
}

private val log = LoggerFactory.getLogger("dev.averyn.backend.ClientConfig")
