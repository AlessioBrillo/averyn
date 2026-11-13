package dev.averyn.client

import dev.averyn.analysis.ActivityAnalysis
import dev.averyn.api.ActivityPage
import dev.averyn.api.ActivitySummary
import dev.averyn.api.ClientConfig
import dev.averyn.api.ConsentGrant
import dev.averyn.api.PrivacyZone
import dev.averyn.api.PrivacyZones
import dev.averyn.api.Track
import dev.averyn.api.TrackGeometry
import dev.averyn.api.UnitSystem
import dev.averyn.api.UserProfile
import dev.averyn.domain.LocationSample
import dev.averyn.domain.Sport
import dev.averyn.sync.SyncStatusStore
import dev.averyn.tracking.ActivityRecorder
import dev.averyn.tracking.ActivityStore
import dev.averyn.tracking.SynapseSidecarKind
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The history of the account: the server, the cache and the device (TDD-0011). */
class HistoryRepositoryTest {
    private lateinit var dir: Path
    private lateinit var store: ActivityStore
    private lateinit var statuses: SyncStatusStore
    private lateinit var reader: FakeReader
    private lateinit var repository: HistoryRepository

    @BeforeTest
    fun setUp() {
        dir = createTestDir("averyn-history-test")
        store = ActivityStore(dir)
        statuses = SyncStatusStore(store)
        reader = FakeReader()
        repository =
            HistoryRepository(
                store = store,
                cache = ActivityCache(Path(dir, "cache"), "https://server.example", nowMs = { 1_000 }),
                client = reader,
                statuses = statuses,
                localProfile = LocalProfileStore(Path(dir, "profile").toString()),
                localZones = LocalZonesStore(Path(dir, "profile").toString()),
                nowMs = { 1_000 },
            )
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
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
        runTest {
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
    fun `an imported file is an import whatever its format and a device recording is not`() =
        runTest {
            reader.page =
                ActivityPage(
                    listOf(
                        summary("d"),
                        summary("g", source = "gpx"),
                        summary("f", source = "fit"),
                        summary("t", source = "tcx"),
                    ),
                    null,
                )
            val sources = repository.refresh("token").items.associate { it.serverId to it.source }
            assertEquals(HistorySource.DEVICE, sources["d"])
            assertEquals(HistorySource.GPX, sources["g"])
            assertEquals(HistorySource.GPX, sources["f"])
            assertEquals(HistorySource.GPX, sources["t"])
        }

    @Test
    fun `an activity of the device that is not on the server yet is in the list`() =
        runTest {
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
    fun `an activity the server deleted is not in the list any more but its file stays`() =
        runTest {
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
        runTest {
            localActivity("local1")
            statuses.write("local1", dev.averyn.sync.SyncStatus(dev.averyn.sync.SyncState.READY))
            reader.page = ActivityPage(listOf(summary("s1", clientId = "local1")), null)
            val snapshot = repository.refresh("token")
            assertEquals(1, snapshot.items.size)
            assertEquals("s1", snapshot.items[0].serverId)
        }

    @Test
    fun `an activity refused by the server as deleted is not in the list`() =
        runTest {
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
        runTest {
            reader.failure = ApiFailure(ApiFailureKind.UNAUTHORIZED, status = 401)
            val snapshot = repository.refresh("token")
            assertTrue(snapshot.needsLogin)
        }

    @Test
    fun `a detail is read from the server and kept in the cache`() =
        runTest {
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
        runTest {
            reader.activity = summary("s1")
            repository.refreshDetail("s1", "token")
            reader.failure = ApiFailure(ApiFailureKind.NOT_FOUND, status = 404)
            val detail = repository.refreshDetail("s1", "token")
            assertTrue(detail.deletedOnServer)
            assertNull(repository.cachedDetail("s1"))
        }

    @Test
    fun `the analysis in progress is a state and not a failure`() =
        runTest {
            reader.activity = summary("s1")
            repository.refreshDetail("s1", "token")
            reader.failure = ApiFailure(ApiFailureKind.BUSY, status = 503, retryAfterSeconds = 5)
            val detail = repository.refreshAnalysis("s1", "token")
            assertEquals(AnalysisState.COMPUTING, detail.analysisState)
            assertEquals(5L, detail.failure?.retryAfterSeconds)
        }

    @Test
    fun `the analysis is shown when the server has it`() =
        runTest {
            reader.activity = summary("s1")
            repository.refreshDetail("s1", "token")
            reader.analysis = analysis()
            val detail = repository.refreshAnalysis("s1", "token")
            assertEquals(AnalysisState.READY, detail.analysisState)
            assertNotNull(detail.analysis)
        }

    @Test
    fun `deleting an activity on the device removes its files`() =
        runTest {
            localActivity("local1")
            store.appendSynapse("local1", SynapseSidecarKind.RAW, byteArrayOf(1))
            store.appendSynapse("local1", SynapseSidecarKind.FEATURES, byteArrayOf(1))
            repository.deleteLocal("local1")
            assertTrue(store.list().isEmpty())
            assertTrue(SynapseSidecarKind.entries.none { store.hasSynapse("local1", it) })
            assertNull(store.readLocalSummary("local1"))
            assertTrue(repository.deviceData().isEmpty())
        }

    @Test
    fun `the activity being recorded is not deleted`() =
        runTest {
            localActivity("local1")
            assertFalse(repository.deleteLocal("local1", inProgressId = "local1"))
            assertEquals(1, store.list().size)
        }

    @Test
    fun `the copy of the server can be thrown away`() =
        runTest {
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
        runTest {
            assertEquals(UnitSystem.METRIC, repository.cachedUnits())
            reader.profile = UserProfile(UnitSystem.IMPERIAL, 187)

            val online = repository.syncProfile("token")

            assertEquals(UserProfile(UnitSystem.IMPERIAL, 187), online.profile)
            assertEquals(UnitSystem.IMPERIAL, online.units)
            reader.failure = ApiFailure(ApiFailureKind.TRANSIENT)
            val offline = repository.syncProfile("token")
            assertEquals(UserProfile(UnitSystem.IMPERIAL, 187), offline.profile) // the phone's own copy
            assertTrue(offline.fromCache)
            assertEquals(UnitSystem.IMPERIAL, offline.units)
            assertEquals(UnitSystem.IMPERIAL, repository.cachedUnits())
        }

    @Test
    fun `without a token the profile is not asked for and the cached units are used`() =
        runTest {
            reader.profile = UserProfile(UnitSystem.IMPERIAL, 187)
            repository.syncProfile("token")

            val snapshot = repository.syncProfile(null)

            assertEquals(UserProfile(UnitSystem.IMPERIAL, 187), snapshot.profile)
            assertEquals(UnitSystem.IMPERIAL, snapshot.units)
        }

    @Test
    fun `a saved profile changes the units and a refused one changes nothing`() =
        runTest {
            val saved = repository.saveProfileLocalFirst("token", UserProfile(UnitSystem.IMPERIAL, 190))
            assertEquals(UserProfile(UnitSystem.IMPERIAL, 190), saved.profile)
            assertEquals(UnitSystem.IMPERIAL, repository.cachedUnits())

            // A maximum of 99 is refused on the phone, before any call: nothing changes, the server is not asked.
            val refused = repository.saveProfileLocalFirst("token", UserProfile(UnitSystem.METRIC, 99))

            assertNull(refused.profile)
            assertEquals(422, refused.failure?.status)
            assertEquals(UnitSystem.IMPERIAL, refused.units)
            assertEquals(UnitSystem.IMPERIAL, repository.cachedUnits())
        }

    @Test
    fun `an opened activity carries the map of its track also from the copy`() =
        runTest {
            reader.activity = summary("s1")
            reader.track =
                Track(geometry = TrackGeometry(coordinates = listOf(listOf(listOf(9.1, 45.4), listOf(9.2, 45.5)))))

            val online = repository.refreshDetail("s1", "token")
            val onlineMap = assertNotNull(online.track)
            assertEquals(45.5, onlineMap.maxLatitude)

            // Without a connection the copy still draws it.
            val offline = assertNotNull(repository.cachedDetail("s1"))
            assertEquals(45.4, assertNotNull(offline.track).minLatitude)
        }

    @Test
    fun `an activity without a track has no map`() =
        runTest {
            reader.activity = summary("s1")
            reader.track = null

            assertNull(repository.refreshDetail("s1", "token").track)
        }

    @Test
    fun `deleting the account clears the copy of the server and keeps the recordings on the device`() =
        runTest {
            reader.page = ActivityPage(listOf(summary("s1")), null)
            repository.refresh("token")
            assertEquals(1, repository.loadCached().items.size)

            val result = repository.deleteAccount("token")

            assertNull(result.failure)
            assertTrue(reader.accountDeleted)
            assertEquals(0, repository.loadCached().items.size)
        }

    @Test
    fun `a failed deletion leaves the copy where it is so the user can try again`() =
        runTest {
            reader.page = ActivityPage(listOf(summary("s1")), null)
            repository.refresh("token")
            reader.failure = ApiFailure(ApiFailureKind.TRANSIENT)

            val result = repository.deleteAccount("token")

            assertEquals(ApiFailureKind.TRANSIENT, result.failure?.kind)
            assertEquals(false, reader.accountDeleted)
            reader.failure = null
            assertEquals(1, repository.loadCached().items.size)
        }

    private val home = PrivacyZone(45.4, 9.1, 200, "Home")

    @Test
    fun `a zone made on the phone hides the start of a track at once and works with no account`() =
        runTest {
            reader.activity = summary("s1")
            // 0.001 degrees of latitude are about 111 m: the first two points are inside 200 m of the zone.
            reader.track =
                Track(
                    geometry =
                        TrackGeometry(
                            coordinates = listOf(List(5) { listOf(9.1, 45.4 + it * 0.001) }),
                        ),
                )
            assertEquals(45.4, assertNotNull(repository.refreshDetail("s1", "token").track).minLatitude)

            val saved = repository.savePrivacyZones(null, PrivacyZones(listOf(home)))

            assertTrue(saved.fromCache)
            assertEquals(PrivacyZones(listOf(home)), repository.privacyZones())
            // The copy taken before the zone existed is shown with the zone applied.
            assertEquals(45.402, assertNotNull(repository.cachedDetail("s1")?.track).minLatitude, 1e-9)
            assertEquals(45.402, assertNotNull(repository.refreshDetail("s1", "token").track).minLatitude, 1e-9)
        }

    @Test
    fun `zones that are not valid change nothing and are not sent`() =
        runTest {
            repository.savePrivacyZones(null, PrivacyZones(listOf(home)))

            val refused = repository.savePrivacyZones("token", PrivacyZones(listOf(home.copy(radiusM = 5))))

            assertEquals(422, refused.failure?.status)
            assertEquals(PrivacyZones(listOf(home)), refused.zones)
            assertEquals(null, reader.savedZones)
        }

    @Test
    fun `zones saved offline go up with the next sync and the server's are taken otherwise`() =
        runTest {
            repository.savePrivacyZones(null, PrivacyZones(listOf(home)))

            val sent = repository.syncPrivacyZones("token")

            assertEquals(PrivacyZones(listOf(home)), reader.savedZones)
            assertEquals(false, sent.fromCache)
            // Synced: the next sync reads the server's list instead, which another device may have changed.
            reader.zones = PrivacyZones(listOf(home.copy(radiusM = 500)))
            assertEquals(
                500,
                repository
                    .syncPrivacyZones("token")
                    .zones.zones
                    .single()
                    .radiusM,
            )
            assertEquals(
                500,
                repository
                    .privacyZones()
                    .zones
                    .single()
                    .radiusM,
            )
            // Offline, the phone's copy is what it has.
            reader.failure = ApiFailure(ApiFailureKind.TRANSIENT)
            val offline = repository.syncPrivacyZones("token")
            assertTrue(offline.fromCache)
            assertEquals(
                500,
                offline.zones.zones
                    .single()
                    .radiusM,
            )
        }

    @Test
    fun `a zone edited while the zones are being sent is not overwritten by the answer`() =
        runTest {
            repository.savePrivacyZones(null, PrivacyZones(listOf(home)))
            reader.beforeSavingZones = {
                LocalZonesStore(Path(dir, "profile").toString())
                    .write(StoredZones(PrivacyZones(listOf(home.copy(radiusM = 300))), unsynced = true))
            }

            val answer = repository.syncPrivacyZones("token")

            assertEquals(
                300,
                answer.zones.zones
                    .single()
                    .radiusM,
            )
            val kept = LocalZonesStore(Path(dir, "profile").toString()).read()
            assertEquals(
                300,
                kept.zones.zones
                    .single()
                    .radiusM,
            )
            assertTrue(kept.unsynced, "the newer edit still has to go up")
        }

    @Test
    fun `the progress sees the whole history and not only the first page`() =
        runTest {
            reader.pages =
                mapOf(
                    null to ActivityPage(listOf(summary("s1", startedAt = "2026-03-03T09:00:00Z")), "c2"),
                    "c2" to ActivityPage(listOf(summary("s2", startedAt = "2026-02-02T09:00:00Z")), "c3"),
                    "c3" to ActivityPage(listOf(summary("s3", startedAt = "2026-01-01T09:00:00Z")), null),
                )

            val snapshot = repository.refreshAll("token")

            assertEquals(listOf("s1", "s2", "s3"), snapshot.items.mapNotNull { it.serverId })
            assertEquals(null, snapshot.nextCursor)
            assertEquals(3, reader.listCalls)
            assertEquals(3, repository.loadCached().items.size)
            val months = progressTotals(snapshot.items, ProgressPeriod.MONTH, offsetMsAt = { 0L })
            assertEquals(3, months.size)
        }

    @Test
    fun `a full read drops what the server deleted and a failed one keeps what was cached`() =
        runTest {
            reader.pages =
                mapOf(
                    null to
                        ActivityPage(
                            listOf(summary("s1"), summary("s2", startedAt = "2026-01-30T09:00:00Z")),
                            null,
                        ),
                )
            repository.refreshAll("token")
            assertEquals(2, repository.loadCached().items.size)

            reader.pages = mapOf(null to ActivityPage(listOf(summary("s1")), null)) // s2 was deleted on the web
            assertEquals(listOf("s1"), repository.refreshAll("token").items.mapNotNull { it.serverId })

            reader.failure = ApiFailure(ApiFailureKind.TRANSIENT)
            val failed = repository.refreshAll("token")
            assertTrue(failed.fromCache)
            assertEquals(listOf("s1"), failed.items.mapNotNull { it.serverId })
        }

    @Test
    fun `an edit made while the profile is being sent is not overwritten by the answer`() =
        runTest {
            reader.profile = UserProfile(UnitSystem.METRIC, 180)
            repository.saveProfileLocalFirst(null, UserProfile(UnitSystem.IMPERIAL, 190)) // unsynced, no account yet
            // The user edits again while the call of the first sync is on its way.
            reader.beforeSavingProfile = {
                LocalProfileStore(Path(dir, "profile").toString())
                    .write(StoredProfile(UserProfile(UnitSystem.METRIC, 175), unsynced = true))
            }

            val answer = repository.syncProfile("token")

            assertEquals(UserProfile(UnitSystem.METRIC, 175), answer.profile)
            val kept = LocalProfileStore(Path(dir, "profile").toString()).read()
            assertEquals(UserProfile(UnitSystem.METRIC, 175), kept.profile)
            assertTrue(kept.unsynced, "the newer edit still has to go up")
        }

    private class FakeReader : ActivityReader {
        var zones = PrivacyZones()
        var savedZones: PrivacyZones? = null
        var beforeSavingZones: (() -> Unit)? = null

        override suspend fun privacyZones(accessToken: String): ApiResult<PrivacyZones> =
            failure?.let { ApiResult(failure = it) } ?: ApiResult(zones)

        override suspend fun savePrivacyZones(
            accessToken: String,
            zones: PrivacyZones,
        ): ApiResult<PrivacyZones> {
            failure?.let { return ApiResult(failure = it) }
            beforeSavingZones?.invoke()
            this.zones = zones
            savedZones = zones
            return ApiResult(zones)
        }

        var page: ActivityPage = ActivityPage(emptyList(), null)

        /** When set, the page for a cursor (null is the first); the requests made are counted in [listCalls]. */
        var pages: Map<String?, ActivityPage>? = null
        var listCalls = 0
        var activity: ActivitySummary? = null
        var analysis: ActivityAnalysis? = null
        var track: Track? = null
        var failure: ApiFailure? = null

        override suspend fun clientConfig(): ApiResult<ClientConfig> = ApiResult(ClientConfig("i", "c", "w", "s"))

        override suspend fun listActivities(
            accessToken: String,
            cursor: String?,
            limit: Int,
        ): ApiResult<ActivityPage> {
            listCalls++
            failure?.let { return ApiResult(failure = it) }
            return ApiResult(pages?.get(cursor) ?: page)
        }

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
        var accountDeleted = false

        override suspend fun deleteAccount(accessToken: String): ApiResult<Unit> {
            failure?.let { return ApiResult(failure = it) }
            accountDeleted = true
            return ApiResult(Unit)
        }

        var savedProfile: UserProfile? = null
        var beforeSavingProfile: (() -> Unit)? = null

        override suspend fun profile(accessToken: String): ApiResult<UserProfile> =
            failure?.let { ApiResult(failure = it) } ?: ApiResult(profile)

        override suspend fun saveProfile(
            accessToken: String,
            profile: UserProfile,
        ): ApiResult<UserProfile> {
            failure?.let { return ApiResult(failure = it) }
            beforeSavingProfile?.invoke()
            this.profile = profile
            savedProfile = profile
            return ApiResult(profile)
        }
    }
}
