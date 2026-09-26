package dev.averyn.backend

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ObservabilityTest {
    @Test
    fun healthIsUpAndReadyFollowsDependencies() =
        testApplication {
            var ready = true
            application {
                content()
                observability { ready }
            }

            val health = client.get("/health")
            assertEquals(HttpStatusCode.OK, health.status)
            assertTrue(health.headers.contains("X-Request-Id"))

            assertEquals(HttpStatusCode.OK, client.get("/ready").status)
            ready = false
            assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/ready").status)

            assertTrue(client.get("/metrics").bodyAsText().contains("jvm_"))
        }
}
