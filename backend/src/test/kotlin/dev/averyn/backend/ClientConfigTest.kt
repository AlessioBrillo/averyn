package dev.averyn.backend

import dev.averyn.api.Api
import dev.averyn.api.ClientConfig
import dev.averyn.backend.auth.OidcConfig
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The public configuration of the server (TDD-0011): what an app learns before it signs in. */
class ClientConfigTest {
    private val oidc =
        OidcConfig(
            issuer = "https://idp.example",
            audience = "averyn",
            clientId = "app",
            webClientId = "web",
            internalUrl = null,
        )

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun theConfigurationCarriesTheVersions() =
        testApplication {
            application {
                content()
                clientConfig(oidc, "https://style.example/style.json", "0.2.0")
            }

            val response = client.get("/v1/client-config")
            assertEquals(HttpStatusCode.OK, response.status)
            val config = json.decodeFromString(ClientConfig.serializer(), response.bodyAsText())
            assertEquals("https://idp.example", config.issuer)
            assertEquals(Api.API_VERSION, config.apiVersion)
            assertEquals("0.2.0", config.minClientVersion)
        }

    @Test
    fun withoutAMinimumThereIsNone() =
        testApplication {
            application {
                content()
                clientConfig(oidc, "https://style.example/style.json")
            }

            val config = json.decodeFromString(ClientConfig.serializer(), client.get("/v1/client-config").bodyAsText())
            assertNull(config.minClientVersion)
            assertEquals(Api.API_VERSION, config.apiVersion)
        }

    @Test
    fun aMinimumThatIsNotAVersionIsIgnored() =
        testApplication {
            application {
                content()
                clientConfig(oidc, "https://style.example/style.json", "not-a-version")
            }

            val config = json.decodeFromString(ClientConfig.serializer(), client.get("/v1/client-config").bodyAsText())
            // A configuration mistake must not take the server down: the minimum is simply not there.
            assertNull(config.minClientVersion)
        }
}
