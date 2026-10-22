package dev.averyn.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The contract of `shared/api` (TDD-0011): the JSON the server writes must decode here, an old server must not
 * break a new client, and the version comparison must say what it means.
 */
class ApiContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val summaryJson =
        """
        {"id":"8f14e45f-ea0f-4c8f-9d2a-1b2c3d4e5f60","clientActivityId":"a1b2c3d4-0000-4000-8000-000000000001",
        "sport":"RUN","startedAt":"2026-01-31T09:15:00Z","finalState":"COMPLETED","distanceM":5231.4,
        "elapsedMs":1800000,"movingMs":1500000,"pausedMs":120000,"stationaryMs":180000,
        "averageSpeedMps":3.4876,"paceSecPerKm":286.7,"qualityGrade":"GOOD","droppedRecords":0,
        "metricsAlgorithmVersion":"distance-v1,time-v1,speed-v1","qualityAlgorithmVersion":"quality-v2",
        "visibility":"PRIVATE","avgHrBpm":148,"maxHrBpm":171,"hrSource":"ble-hr",
        "hrAlgorithmVersion":"hr-v2","source":"device"}
        """.trimIndent()

    @Test
    fun `a summary of the server decodes into the shared DTO`() {
        val summary = json.decodeFromString(ActivitySummary.serializer(), summaryJson)
        assertEquals("RUN", summary.sport)
        assertEquals("2026-01-31T09:15:00Z", summary.startedAt)
        assertEquals(5231.4, summary.distanceM)
        assertEquals(148, summary.avgHrBpm)
        assertEquals("device", summary.source)
    }

    @Test
    fun `a field the client does not know is ignored`() {
        val withExtra = summaryJson.replace("\"source\":\"device\"", "\"source\":\"device\",\"futureField\":42")
        val summary = json.decodeFromString(ActivitySummary.serializer(), withExtra)
        assertEquals("device", summary.source)
    }

    @Test
    fun `a summary without the newer optional fields still decodes`() {
        val older = summaryJson.replace("\"source\":\"device\",", "")
        val summary = json.decodeFromString(ActivitySummary.serializer(), older)
        assertEquals("device", summary.source)
    }

    @Test
    fun `a page decodes with its cursor`() {
        val page = json.decodeFromString(ActivityPage.serializer(), "{\"items\":[$summaryJson],\"nextCursor\":\"c1\"}")
        assertEquals(1, page.items.size)
        assertEquals("c1", page.nextCursor)
    }

    @Test
    fun `the track decodes as the GeoJSON feature the server sends`() {
        val body =
            """{"type":"Feature","properties":{},"geometry":{"type":"MultiLineString",
            "coordinates":[[[7.1,46.9],[7.2,47.0]]]}}"""
        val track = json.decodeFromString(Track.serializer(), body)
        assertEquals("Feature", track.type)
        val geometry = track.geometry
        assertTrue(geometry != null)
        assertEquals("MultiLineString", geometry.type)
        assertEquals(1, geometry.coordinates.size)
        assertEquals(listOf(7.1, 46.9), geometry.coordinates[0][0])
    }

    @Test
    fun `an activity without a track has no geometry`() {
        val track = json.decodeFromString(Track.serializer(), """{"type":"Feature","properties":{},"geometry":null}""")
        assertNull(track.geometry)
    }

    @Test
    fun `the client config of an old server still decodes`() {
        val config =
            json.decodeFromString(
                ClientConfig.serializer(),
                """{"issuer":"https://idp","clientId":"app","webClientId":"web","mapStyleUrl":"https://style"}""",
            )
        assertEquals(Api.API_VERSION, config.apiVersion)
        assertNull(config.minClientVersion)
    }

    @Test
    fun `the client config carries the versions`() {
        val config =
            json.decodeFromString(
                ClientConfig.serializer(),
                """{"issuer":"https://idp","clientId":"app","webClientId":"web","mapStyleUrl":"https://style",
                "apiVersion":2,"minClientVersion":"0.2.0"}""",
            )
        assertEquals(2, config.apiVersion)
        assertEquals("0.2.0", config.minClientVersion)
    }

    @Test
    fun `versions are compared the way the packaging rules say`() {
        assertEquals(SemanticVersion(1, 0, 0), SemanticVersion.parseOrNull("1.0.0"))
        assertEquals(SemanticVersion(1, 10, 0), SemanticVersion.parseOrNull("1.10.0"))
        assertEquals(SemanticVersion(0, 1, 0), SemanticVersion.parseOrNull("0.1"))
        assertTrue(SemanticVersion.parseOrNull("1.0.0")!! > SemanticVersion.parseOrNull("0.9.9")!!)
        assertTrue(SemanticVersion.parseOrNull("1.10.0")!! > SemanticVersion.parseOrNull("1.9.9")!!)
        assertTrue(SemanticVersion.parseOrNull("1.0.0")!! > SemanticVersion.parseOrNull("1.0.0-alpha")!!)
        assertTrue(SemanticVersion.parseOrNull("1.0.0-alpha")!! < SemanticVersion.parseOrNull("1.0.0")!!)
        assertTrue(SemanticVersion.parseOrNull("1.0.0")!! == SemanticVersion.parseOrNull("1.0.0+build7")!!)
    }

    @Test
    fun `a version that is not one is not a version`() {
        assertNull(SemanticVersion.parseOrNull(null))
        assertNull(SemanticVersion.parseOrNull(""))
        assertNull(SemanticVersion.parseOrNull("unknown"))
        assertNull(SemanticVersion.parseOrNull("1.x.0"))
    }

    @Test
    fun `the compatibility of a matching pair is fine`() {
        assertEquals(
            ClientCompatibility.OK,
            Api.checkCompatibility(
                serverApiVersion = Api.API_VERSION,
                minClientVersion = "0.0.1",
                clientVersion = "0.0.1",
            ),
        )
    }

    @Test
    fun `an app below the minimum of the server is warned`() {
        assertEquals(
            ClientCompatibility.APP_BELOW_MINIMUM,
            Api.checkCompatibility(
                serverApiVersion = Api.API_VERSION,
                minClientVersion = "0.2.0",
                clientVersion = "0.1.0",
            ),
        )
    }

    @Test
    fun `a server with a newer contract warns the app`() {
        assertEquals(
            ClientCompatibility.APP_TOO_OLD,
            Api.checkCompatibility(serverApiVersion = Api.API_VERSION + 1, supportedApiVersion = Api.API_VERSION),
        )
    }

    @Test
    fun `a server with an older contract warns too`() {
        assertEquals(
            ClientCompatibility.SERVER_TOO_OLD,
            Api.checkCompatibility(serverApiVersion = Api.API_VERSION - 1, supportedApiVersion = Api.API_VERSION),
        )
    }

    @Test
    fun `an unreadable version claims nothing`() {
        assertEquals(
            ClientCompatibility.OK,
            Api.checkCompatibility(
                serverApiVersion = Api.API_VERSION,
                minClientVersion = "not-a-version",
                clientVersion = "0.1.0",
            ),
        )
    }
}
