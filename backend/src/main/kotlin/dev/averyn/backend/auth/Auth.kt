package dev.averyn.backend.auth

import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkException
import com.auth0.jwk.JwkProvider
import com.auth0.jwk.JwkProviderBuilder
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/** The OIDC provider Averyn trusts (ADR-0011). [audience] must appear in a token's `aud`; [clientId] is for apps. */
data class OidcConfig(
    val issuer: String,
    val audience: String,
    val clientId: String,
)

/** Name of the authentication provider that protects every `/v1` route except `/v1/client-config`. */
const val OIDC_AUTH = "oidc"

/**
 * Bearer-JWT authentication against the issuer's published keys: signature, `iss`, `aud` and `exp` (30 s
 * leeway) are checked, and a token without a subject is rejected. Tokens never reach the logs (CallLogging
 * records method, path and status only). [jwks] is injectable so tests don't need an identity provider.
 */
fun Application.auth(
    config: OidcConfig,
    jwks: JwkProvider = discoveredJwks(config.issuer),
) {
    install(Authentication) {
        jwt(OIDC_AUTH) {
            realm = "averyn"
            verifier(jwks, config.issuer) {
                withAudience(config.audience)
                acceptLeeway(30)
            }
            validate { credential -> credential.payload.subject?.let { JWTPrincipal(credential.payload) } }
        }
    }
}

/**
 * Keys are looked up through the issuer's `/.well-known/openid-configuration` the first time a token needs
 * verifying, not at start-up: the identity provider may still be coming up. While it is unreachable, token
 * checks fail fast (the lookup is not repeated for [retryAfter]), so a down IdP cannot tie up request threads;
 * requests meanwhile get 401. Lookups are serialized: at most one blocks while the IdP is slow.
 */
fun discoveredJwks(
    issuer: String,
    retryAfter: Duration = Duration.ofSeconds(15),
): JwkProvider =
    object : JwkProvider {
        private var delegate: JwkProvider? = null
        private var failedAt: Instant? = null

        @Synchronized
        override fun get(keyId: String?): Jwk = (delegate ?: resolve()).get(keyId)

        private fun resolve(): JwkProvider {
            val failure = failedAt
            if (failure != null && Duration.between(failure, Instant.now()) < retryAfter) {
                throw JwkException("identity provider keys unavailable")
            }
            return try {
                JwkProviderBuilder(URI(jwksUri(issuer)).toURL())
                    .cached(10, 24, TimeUnit.HOURS)
                    .rateLimited(10, 1, TimeUnit.MINUTES)
                    .build()
                    .also {
                        delegate = it
                        failedAt = null
                    }
            } catch (e: Exception) {
                failedAt = Instant.now()
                throw JwkException("identity provider keys unavailable", e)
            }
        }
    }

private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

private fun jwksUri(issuer: String): String {
    val request =
        HttpRequest
            .newBuilder(URI("${issuer.trimEnd('/')}/.well-known/openid-configuration"))
            .timeout(Duration.ofSeconds(3))
            .build()
    val response = http.send(request, HttpResponse.BodyHandlers.ofString())
    check(response.statusCode() == 200) { "OIDC discovery failed: HTTP ${response.statusCode()}" }
    val doc = Json.parseToJsonElement(response.body()).jsonObject
    // OIDC Discovery 4.3: a document whose issuer differs from the one asked for must not be trusted.
    check(doc.getValue("issuer").jsonPrimitive.content == issuer) { "OIDC discovery issuer mismatch" }
    return doc.getValue("jwks_uri").jsonPrimitive.content
}
