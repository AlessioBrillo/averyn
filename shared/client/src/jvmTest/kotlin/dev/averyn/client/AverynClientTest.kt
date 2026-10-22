package dev.averyn.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The read path of the server: the answers, the repetitions and the states (TDD-0011). */
class AverynClientTest {
    private val summaryJson =
        """
        {"id":"s1","clientActivityId":"c1","sport":"RUN","startedAt":"2026-01-31T09:15:00Z",
        "finalState":"COMPLETED","distanceM":1000.0,"elapsedMs":600000,"movingMs":500000,"pausedMs":60000,
        "stationaryMs":40000,"averageSpeedMps":2.0,"paceSecPerKm":500.0,"qualityGrade":"GOOD","droppedRecords":0,
        "metricsAlgorithmVersion":"distance-v1,time-v1,speed-v1","qualityAlgorithmVersion":"quality-v2",
        "visibility":"PRIVATE","avgHrBpm":0,"maxHrBpm":0,"hrSource":"none","hrAlgorithmVersion":"hr-v2"}
        """.trimIndent()

    private fun client(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): Pair<AverynClient, MutableList<Long>> {
        val waits = mutableListOf<Long>()
        val engine =
            MockEngine { request ->
                handler(request)
            }
        val http = HttpClient(engine)
        return AverynClient(
            serverUrl = "https://server.example",
            http = http,
            sleeper = { waits.add(it) },
            nowMs = { 0L },
        ) to waits
    }

    @Test
    fun `the list is read with the token`() =
        runBlocking {
            var seen: HttpRequestData? = null
            val (client, _) =
                client { request ->
                    seen = request
                    respond(
                        """{"items":[$summaryJson],"nextCursor":null}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val result = client.listActivities("token", cursor = null, limit = 10)
            assertNotNull(result.value)
            assertEquals(1, result.value.items.size)
            assertEquals("Bearer token", seen?.headers?.get("Authorization"))
            assertEquals("/v1/activities", seen?.url?.encodedPath)
            assertEquals("limit=10", seen?.url?.encodedQuery)
        }

    @Test
    fun `a refused token is not repeated`() =
        runBlocking {
            var calls = 0
            val (client, _) =
                client {
                    calls++
                    respond("{}", HttpStatusCode.Unauthorized)
                }
            val result = client.activity("token", "s1")
            assertEquals(1, calls)
            assertEquals(ApiFailureKind.UNAUTHORIZED, result.failure?.kind)
        }

    @Test
    fun `an activity that does not exist is not repeated`() =
        runBlocking {
            var calls = 0
            val (client, _) =
                client {
                    calls++
                    respond("{}", HttpStatusCode.NotFound)
                }
            val result = client.activity("token", "s1")
            assertEquals(1, calls)
            assertEquals(ApiFailureKind.NOT_FOUND, result.failure?.kind)
        }

    @Test
    fun `a busy server is repeated and the wait is the one it asked for`() =
        runBlocking {
            var calls = 0
            val (client, waits) =
                client {
                    calls++
                    if (calls < 3) {
                        respond("{}", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "5"))
                    } else {
                        respond(summaryJson, HttpStatusCode.OK)
                    }
                }
            val result = client.activity("token", "s1")
            assertEquals(3, calls)
            assertNotNull(result.value)
            assertEquals(listOf(5_000L, 5_000L), waits)
        }

    @Test
    fun `the analysis in progress is a state and not a repetition`() =
        runBlocking {
            var calls = 0
            val (client, _) =
                client {
                    calls++
                    respond("{}", HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.RetryAfter, "5"))
                }
            val result = client.analysis("token", "s1")
            assertEquals(1, calls)
            assertEquals(ApiFailureKind.BUSY, result.failure?.kind)
            assertEquals(5L, result.failure?.retryAfterSeconds)
        }

    @Test
    fun `a temporary failure is repeated at most twice`() =
        runBlocking {
            var calls = 0
            val (client, _) =
                client {
                    calls++
                    respond("{}", HttpStatusCode.BadGateway)
                }
            val result = client.activity("token", "s1")
            assertEquals(3, calls)
            assertEquals(ApiFailureKind.TRANSIENT, result.failure?.kind)
        }

    @Test
    fun `the configuration of the server carries the versions`() =
        runBlocking {
            val (client, _) =
                client {
                    respond(
                        """{"issuer":"https://idp","clientId":"app","webClientId":"web","mapStyleUrl":"https://style",
                        "apiVersion":2,"minClientVersion":"0.2.0"}""",
                        HttpStatusCode.OK,
                    )
                }
            val config = client.clientConfig()
            assertNotNull(config.value)
            assertEquals(2, config.value.apiVersion)
            assertEquals("0.2.0", config.value.minClientVersion)
        }

    @Test
    fun `an answer that is not the contract is a failure, not a crash`() =
        runBlocking {
            val (client, _) =
                client {
                    respond("not json at all", HttpStatusCode.OK)
                }
            val result = client.activity("token", "s1")
            assertEquals(ApiFailureKind.UNEXPECTED, result.failure?.kind)
        }

    @Test
    fun `the track is read as GeoJSON`() =
        runBlocking {
            val (client, _) =
                client {
                    respond(
                        """{"type":"Feature","properties":{},"geometry":{"type":"MultiLineString",
                        "coordinates":[[[7.1,46.9]]]}}""",
                        HttpStatusCode.OK,
                    )
                }
            val result = client.track("token", "s1")
            assertNotNull(result.value)
            assertEquals("MultiLineString", result.value.geometry?.type)
            assertTrue(
                result.value.geometry
                    ?.coordinates
                    ?.isNotEmpty() == true,
            )
        }
}
