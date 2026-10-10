package dev.averyn.backend.activities

import dev.averyn.backend.auth.TestIssuer
import dev.averyn.backend.auth.auth
import dev.averyn.backend.content
import dev.averyn.backend.storage.RawObjectStore
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.postgresql.ds.PGSimpleDataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** TDD-0005 §5.4. No database or object store: a malformed id is answered before either is touched. */
class RateLimitTest {
    private val idp = TestIssuer()

    @Test
    fun aUserOverTheLimitGets429WhileOtherUsersAreUnaffected() =
        testApplication {
            application {
                content()
                auth(idp.config, idp.jwks)
                activities(
                    PGSimpleDataSource(),
                    RawObjectStore("http://127.0.0.1:1", "b", "k", "s"),
                    requestsPerMinute = 3,
                )
            }
            val alice = idp.token(subject = "alice")
            repeat(3) {
                assertEquals(HttpStatusCode.BadRequest, client.get("/v1/activities/x") { bearerAuth(alice) }.status)
            }

            val limited = client.get("/v1/activities/x") { bearerAuth(alice) }
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertNotNull(limited.headers[HttpHeaders.RetryAfter])

            val bob = idp.token(subject = "bob")
            assertEquals(HttpStatusCode.BadRequest, client.get("/v1/activities/x") { bearerAuth(bob) }.status)
            // Authentication comes first: without a valid token it stays 401, never 429.
            assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/activities/x").status)
        }
}
