package dev.averyn.backend.auth

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
