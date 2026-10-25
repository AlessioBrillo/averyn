package dev.averyn.client

import dev.averyn.analysis.ActivityAnalysis
import dev.averyn.api.ActivityPage
import dev.averyn.api.ActivitySummary
import dev.averyn.api.ClientConfig
import dev.averyn.api.ConsentGrant
import dev.averyn.api.Track
import dev.averyn.api.UnitSystem
import dev.averyn.api.UserProfile
import dev.averyn.domain.LocationSample
import dev.averyn.domain.Sport
import dev.averyn.sync.SyncStatusStore
import dev.averyn.tracking.ActivityRecorder
import dev.averyn.tracking.ActivityStore
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The history of the account: the server, the cache and the device (TDD-0011). */
class HistoryRepositoryTest {
    private lateinit var dir: java.nio.file.Path
    private lateinit var store: ActivityStore
    private lateinit var statuses: SyncStatusStore
    private lateinit var reader: FakeReader
    private lateinit var repository: HistoryRepository

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("averyn-history-test")
        store = ActivityStore(Path(dir.toString()))
        statuses = SyncStatusStore(store)
        reader = FakeReader()
        repository =
            HistoryRepository(
                store = store,
                cache = ActivityCache(Path(dir.toString(), "cache"), "https://server.example", nowMs = { 1_000 }),
                client = reader,
                statuses = statuses,
                nowMs = { 1_000 },
            )
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun summary(
        id: String,
        clientId: String = id,
        startedAt: String = "2026-01-31T09:15:00Z",
        source: String = "device",
    ) = ActivitySummary(
        id = id,
        clientActivityId = clientId,
        sport = "RUN",
        startedAt = startedAt,
        finalState = "COMPLETED",
        distanceM = 1000.0,
        elapsedMs = 600_000,
        movingMs = 500_000,
        pausedMs = 60_000,
        stationaryMs = 40_000,
        averageSpeedMps = 2.0,
        paceSecPerKm = 500.0,
        qualityGrade = "GOOD",
        droppedRecords = 0,
        metricsAlgorithmVersion = "distance-v1,time-v1,speed-v1",
        qualityAlgorithmVersion = "quality-v2",
        visibility = "PRIVATE",
        avgHrBpm = 140,
        maxHrBpm = 160,
        hrSource = "ble-hr",
        hrAlgorithmVersion = "hr-v2",
        source = source,
    )

    /** A finished activity on this device, with its file and its local summary. */
    private fun localActivity(id: String) {
        val recorder = ActivityRecorder(store)
        recorder.start(id, Sport.RUN, 0, 1_000)
        recorder.gpsReady(0, 1_000)
        recorder.onSample(LocationSample(1_000, 0, 45.0, 9.0, 5.0))
        recorder.onSample(LocationSample(2_000, 1_000, 45.001, 9.0, 5.0))
        recorder.stop(3_000, 4_000)
    }

    @Test
    fun `the list of the server is shown and cached`() =
        runBlocking {
            reader.page = ActivityPage(listOf(summary("s1")), null)
            val snapshot = repository.refresh("token")
            assertEquals(1, snapshot.items.size)
            assertEquals("s1", snapshot.items[0].serverId)
            assertEquals(false, snapshot.fromCache)
            // The second read works without the server: the cache answers.
            reader.failure = ApiFailure(ApiFailureKind.TRANSIENT)
            val offline = repository.refresh("token")
            assertEquals(1, offline.items.size)
            assertTrue(offline.fromCache)
            assertNotNull(offline.failure)
        }

    @Test
    fun `an activity of the device that is not on the server yet is in the list`() =
        runBlocking {
            localActivity("local1")
            reader.page = ActivityPage(emptyList(), null)
            val snapshot = repository.refresh("token")
            assertEquals(1, snapshot.items.size)
            val row = snapshot.items[0]
            assertEquals("local1", row.clientActivityId)
            assertEquals(HistorySource.LOCAL, row.source)
            // A finished activity that was not uploaded yet is queued (TDD-0002).
            assertEquals(HistorySyncState.QUEUED, row.syncState)
            // The local summary gives the numbers, without a server.
            assertTrue(row.localEstimate)
            assertTrue(row.distanceM > 0.0)
        }

    @Test
    fun `an activity the server deleted is not in the list any more, but its file stays`() =
        runBlocking {
            localActivity("local1")
            statuses.write("local1", dev.averyn.sync.SyncStatus(dev.averyn.sync.SyncState.READY))
            reader.page = ActivityPage(emptyList(), null)
            val snapshot = repository.refresh("token")
            assertTrue(snapshot.items.none { it.clientActivityId == "local1" })
            val deviceData = repository.deviceData()
            assertEquals(1, deviceData.size)
            assertTrue(deviceData[0].deletedOnServer)
            assertNotNull(deviceData[0].summary)
        }

    @Test
    fun `an activity that is on the server is one row`() =
        runBlocking {
            localActivity("local1")
            statuses.write("local1", dev.averyn.sync.SyncStatus(dev.averyn.sync.SyncState.READY))
            reader.page = ActivityPage(listOf(summary("s1", clientId = "local1")), null)
            val snapshot = repository.refresh("token")
            assertEquals(1, snapshot.items.size)
            assertEquals("s1", snapshot.items[0].serverId)
        }

    @Test
    fun `an activity refused by the server as deleted is not in the list`() =
        runBlocking {
            localActivity("local1")
            statuses.write(
                "local1",
                dev.averyn.sync.SyncStatus(dev.averyn.sync.SyncState.FAILED_PERMANENT, lastError = "DELETED_ON_SERVER"),
            )
            reader.page = ActivityPage(emptyList(), null)
            val snapshot = repository.refresh("token")
            assertTrue(snapshot.items.isEmpty())
            assertTrue(repository.deviceData()[0].deletedOnServer)
        }

    @Test
    fun `the list asks to sign in again when the token is refused`() =
        runBlocking {
            reader.failure = ApiFailure(ApiFailureKind.UNAUTHORIZED, status = 401)
            val snapshot = repository.refresh("token")
            assertTrue(snapshot.needsLogin)
        }

    @Test
    fun `a detail is read from the server and kept in the cache`() =
        runBlocking {
            reader.page = ActivityPage(listOf(summary("s1")), null)
            reader.activity = summary("s1")
            repository.refresh("token")
            val detail = repository.refreshDetail("s1", "token")
            assertEquals("s1", detail.serverId)
            assertNotNull(detail.summary)
            // Offline, the copy answers.
            reader.failure = ApiFailure(ApiFailureKind.TRANSIENT)
            val offline = repository.refreshDetail("s1", "token")
            assertTrue(offline.fromCache)
            assertEquals("s1", offline.serverId)
        }

    @Test
    fun `an activity the server does not have any more is removed from the cache`() =
        runBlocking {
            reader.activity = summary("s1")
            repository.refreshDetail("s1", "token")
            reader.failure = ApiFailure(ApiFailureKind.NOT_FOUND, status = 404)
            val detail = repository.refreshDetail("s1", "token")
            assertTrue(detail.deletedOnServer)
            assertNull(repository.cachedDetail("s1"))
        }

    @Test
    fun `the analysis in progress is a state, not a failure`() =
        runBlocking {
            reader.activity = summary("s1")
            repository.refreshDetail("s1", "token")
            reader.failure = ApiFailure(ApiFailureKind.BUSY, status = 503, retryAfterSeconds = 5)
            val detail = repository.refreshAnalysis("s1", "token")
            assertEquals(AnalysisState.COMPUTING, detail.analysisState)
            assertEquals(5L, detail.failure?.retryAfterSeconds)
        }

    @Test
    fun `the analysis is shown when the server has it`() =
        runBlocking {
            reader.activity = summary("s1")
            repository.refreshDetail("s1", "token")
            reader.analysis = analysis()
            val detail = repository.refreshAnalysis("s1", "token")
            assertEquals(AnalysisState.READY, detail.analysisState)
            assertNotNull(detail.analysis)
        }

    @Test
    fun `deleting an activity on the device removes its files`() =
        runBlocking {
            localActivity("local1")
            repository.deleteLocal("local1")
            assertTrue(store.list().isEmpty())
            assertNull(store.readLocalSummary("local1"))
            assertTrue(repository.deviceData().isEmpty())
        }

    @Test
    fun `the copy of the server can be thrown away`() =
        runBlocking {
            reader.activity = summary("s1")
            repository.refreshDetail("s1", "token")
            repository.clearCache()
            assertNull(repository.cachedDetail("s1"))
        }

    private fun analysis(): ActivityAnalysis {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        return json.decodeFromString(
            ActivityAnalysis.serializer(),
            """
            {"schemaVersion":1,"algorithmVersions":{"splits":"splits-v1","elevation":"elevation-v1",
            "series":"series-v1","heartRate":"hr-v2","bestEfforts":"best-efforts-v1"},
            "splitLengthM":1000.0,"totals":{"distanceM":1000.0,"elapsedMs":600000,"movingMs":500000,
            "pausedMs":60000,"stationaryMs":40000},"elevation":null,"heartRate":null,"splits":[],
            "bestEfforts":[],"series":{"distanceM":[0.0],"elevationM":[null],"elapsedMs":[0],
            "segment":[0],"heartRate":{"elapsedMs":[],"bpm":[],"segment":[]}}}
            """.trimIndent(),
        )
    }

    /** The server, as the tests want it. */
    @Test
    fun `the unit system of the profile is remembered for the next time without a connection`() =
        runBlocking {
            assertEquals(UnitSystem.METRIC, repository.cachedUnits())
            reader.profile = UserProfile(UnitSystem.IMPERIAL, 187)

            val online = repository.refreshProfile("token")

            assertEquals(UserProfile(UnitSystem.IMPERIAL, 187), online.profile)
            assertEquals(UnitSystem.IMPERIAL, online.units)
            reader.failure = ApiFailure(ApiFailureKind.TRANSIENT)
            val offline = repository.refreshProfile("token")
            assertNull(offline.profile)
            assertTrue(offline.fromCache)
            assertEquals(UnitSystem.IMPERIAL, offline.units)
            assertEquals(UnitSystem.IMPERIAL, repository.cachedUnits())
        }

    @Test
    fun `without a token the profile is not asked for and the cached units are used`() =
        runBlocking {
            reader.profile = UserProfile(UnitSystem.IMPERIAL, 187)
            repository.refreshProfile("token")

            val snapshot = repository.refreshProfile(null)

            assertNull(snapshot.profile)
            assertEquals(UnitSystem.IMPERIAL, snapshot.units)
        }

    @Test
    fun `a saved profile changes the units and a refused one changes nothing`() =
        runBlocking {
            val saved = repository.saveProfile("token", UserProfile(UnitSystem.IMPERIAL, 190))
            assertEquals(UserProfile(UnitSystem.IMPERIAL, 190), saved.profile)
            assertEquals(UnitSystem.IMPERIAL, repository.cachedUnits())

            reader.failure = ApiFailure(ApiFailureKind.UNEXPECTED, status = 422)
            val refused = repository.saveProfile("token", UserProfile(UnitSystem.METRIC, 99))

            assertNull(refused.profile)
            assertEquals(422, refused.failure?.status)
            assertEquals(UnitSystem.IMPERIAL, refused.units)
            assertEquals(UnitSystem.IMPERIAL, repository.cachedUnits())
        }

    private class FakeReader : ActivityReader {
        var page: ActivityPage = ActivityPage(emptyList(), null)
        var activity: ActivitySummary? = null
        var analysis: ActivityAnalysis? = null
        var track: Track? = null
        var failure: ApiFailure? = null

        override suspend fun clientConfig(): ApiResult<ClientConfig> = ApiResult(ClientConfig("i", "c", "w", "s"))

        override suspend fun listActivities(
            accessToken: String,
            cursor: String?,
            limit: Int,
        ): ApiResult<ActivityPage> = failure?.let { ApiResult(failure = it) } ?: ApiResult(page)

        override suspend fun activity(
            accessToken: String,
            id: String,
        ): ApiResult<ActivitySummary> =
            failure?.let { ApiResult(failure = it) }
                ?: activity?.let { ApiResult(it) }
                ?: ApiResult(failure = ApiFailure(ApiFailureKind.NOT_FOUND, status = 404))

        override suspend fun track(
            accessToken: String,
            id: String,
        ): ApiResult<Track> = failure?.let { ApiResult(failure = it) } ?: ApiResult(track)

        override suspend fun analysis(
            accessToken: String,
            id: String,
        ): ApiResult<ActivityAnalysis> =
            failure?.let { ApiResult(failure = it) }
                ?: analysis?.let { ApiResult(it) }
                ?: ApiResult(failure = ApiFailure(ApiFailureKind.NOT_FOUND, status = 404))

        override suspend fun consents(accessToken: String): ApiResult<List<ConsentGrant>> =
            failure?.let { ApiResult(failure = it) } ?: ApiResult(emptyList())

        var profile = UserProfile()

        override suspend fun profile(accessToken: String): ApiResult<UserProfile> =
            failure?.let { ApiResult(failure = it) } ?: ApiResult(profile)

        override suspend fun saveProfile(
            accessToken: String,
            profile: UserProfile,
        ): ApiResult<UserProfile> {
            failure?.let { return ApiResult(failure = it) }
            this.profile = profile
            return ApiResult(profile)
        }
    }
}
