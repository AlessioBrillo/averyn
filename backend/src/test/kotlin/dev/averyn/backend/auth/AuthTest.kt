package dev.averyn.backend.auth

import com.sun.net.httpserver.HttpServer
import dev.averyn.backend.clientConfig
import dev.averyn.backend.content
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.net.InetSocketAddress
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthTest {
    private val idp = TestIssuer()

    private fun Application.protectedApp() {
        content()
        auth(idp.config, idp.jwks)
        clientConfig(idp.config, "https://tiles.test/style.json")
        routing {
            authenticate(OIDC_AUTH) {
                get("/whoami") { call.respondText(checkNotNull(call.principal<JWTPrincipal>()).payload.subject) }
            }
        }
    }

    private fun status(token: String?): HttpStatusCode {
        var result: HttpStatusCode? = null
        testApplication {
            application { protectedApp() }
            result = client.get("/whoami") { token?.let { bearerAuth(it) } }.status
        }
        return checkNotNull(result)
    }

    @Test
    fun aValidTokenIsAcceptedAndIdentifiesTheUser() =
        testApplication {
            application { protectedApp() }
            val response = client.get("/whoami") { bearerAuth(idp.token(subject = "alice")) }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("alice", response.bodyAsText())
        }

    @Test
    fun requestsWithoutAValidTokenAreRejected() {
        assertEquals(HttpStatusCode.Unauthorized, status(null))
        assertEquals(HttpStatusCode.Unauthorized, status("not-a-jwt"))
        assertEquals(HttpStatusCode.Unauthorized, status(idp.token(expiresIn = Duration.ofMinutes(-5))))
        assertEquals(HttpStatusCode.Unauthorized, status(idp.token(audience = "someone-else")))
        assertEquals(HttpStatusCode.Unauthorized, status(idp.token(issuer = "https://evil.test")))
        assertEquals(HttpStatusCode.Unauthorized, status(idp.token(subject = null)))
        assertEquals(HttpStatusCode.Unauthorized, status(idp.forgedToken()))
    }

    @Test
    fun anUnreachableIdentityProviderMeansUnauthorizedNeverAServerError() {
        // Nothing listens on port 1: discovery fails. Requests must get 401 (not 500), and after the first
        // failure they fail fast instead of repeating the lookup.
        val down = discoveredJwks("http://127.0.0.1:1")
        val token = idp.token()
        testApplication {
            application {
                content()
                auth(idp.config, down)
                routing { authenticate(OIDC_AUTH) { get("/whoami") { call.respondText("ok") } } }
            }
            repeat(3) {
                assertEquals(HttpStatusCode.Unauthorized, client.get("/whoami") { bearerAuth(token) }.status)
            }
        }
    }

    @Test
    fun keysComeFromTheInternalUrlButTheIssuerMustStillMatch() {
        val good = providerBehindEdge(publishedIssuer = "https://idp.test")
        val evil = providerBehindEdge(publishedIssuer = "https://evil.test")
        try {
            val token = idp.token()
            assertEquals(HttpStatusCode.OK, whoami(discoveredJwks("https://idp.test", good.url()), token))
            assertEquals(HttpStatusCode.Unauthorized, whoami(discoveredJwks("https://idp.test", evil.url()), token))
        } finally {
            good.stop(0)
            evil.stop(0)
        }
    }

    /**
     * An IdP reached over plain HTTP that, like the bundled one, only answers as its public self (`jwks_uri` on
     * https://idp.test) when told the public host with `X-Forwarded-Host`.
     */
    private fun providerBehindEdge(publishedIssuer: String): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val body =
                    when {
                        exchange.requestHeaders.getFirst("X-Forwarded-Host") != "idp.test" -> null
                        exchange.requestURI.path == "/.well-known/openid-configuration" ->
                            """{"issuer":"$publishedIssuer","jwks_uri":"https://idp.test/oauth/v2/keys"}"""
                        exchange.requestURI.path == "/oauth/v2/keys" -> idp.jwksJson
                        else -> null
                    }?.toByteArray()
                exchange.sendResponseHeaders(if (body == null) 404 else 200, body?.size?.toLong() ?: -1)
                exchange.responseBody.use { if (body != null) it.write(body) }
            }
            start()
        }

    private fun HttpServer.url() = "http://127.0.0.1:${address.port}"

    private fun whoami(
        jwks: com.auth0.jwk.JwkProvider,
        token: String,
    ): HttpStatusCode {
        var result: HttpStatusCode? = null
        testApplication {
            application {
                content()
                auth(idp.config, jwks)
                routing { authenticate(OIDC_AUTH) { get("/whoami") { call.respondText("ok") } } }
            }
            result = client.get("/whoami") { bearerAuth(token) }.status
        }
        return checkNotNull(result)
    }

    @Test
    fun clientConfigIsPublic() =
        testApplication {
            application { protectedApp() }
            val response = client.get("/v1/client-config")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"clientId\":\"app-1\""))
            assertTrue(body.contains("\"webClientId\":\"web-1\""))
            assertTrue(body.contains("\"mapStyleUrl\":\"https://tiles.test/style.json\""))
        }
}
