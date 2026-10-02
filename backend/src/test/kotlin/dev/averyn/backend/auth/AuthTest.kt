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
        clientConfig(idp.config)
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
    fun clientConfigIsPublic() =
        testApplication {
            application { protectedApp() }
            val response = client.get("/v1/client-config")
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"clientId\":\"app-1\""))
        }
}
