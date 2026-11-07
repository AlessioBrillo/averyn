package dev.averyn.backend.synapse

import dev.averyn.api.SynapseSummaryDto
import dev.averyn.api.SynapseWindowDto
import dev.averyn.backend.TestStack
import dev.averyn.backend.activities.UserData
import dev.averyn.backend.activities.activities
import dev.averyn.backend.auth.TestIssuer
import dev.averyn.backend.auth.auth
import dev.averyn.backend.consent.ConsentScope
import dev.averyn.backend.consent.consent
import dev.averyn.backend.content
import dev.averyn.domain.LocationSample
import dev.averyn.domain.Sport
import dev.averyn.sensors.encodeSynapseSidecarRecord
import dev.averyn.sensors.parseSynapseFeatureFrame
import dev.averyn.sensors.readSynapseSidecar
import dev.averyn.tracking.ActivityRecorder
import dev.averyn.tracking.ActivityStore
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Synapse sidecar routes against real PostgreSQL and a real S3 API (ADR-0030); Docker is required. */
class SynapseRoutesTest {
    private val db = TestStack.db
    private val raw = TestStack.raw
    private val idp = TestIssuer()
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var dir: java.nio.file.Path
    private val alice get() = idp.token(subject = "alice")
    private val bob get() = idp.token(subject = "bob")

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("averyn-synapse-test")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
        db.connection.use { it.createStatement().execute("TRUNCATE activities, users CASCADE") }
    }

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/synapse/$name")) { "missing $name" }.readBytes()

    private val rawSidecar by lazy { resource("session_60s.synapse-raw.bin") }
    private val featureSidecar by lazy { resource("session_60s.synapse.bin") }

    /** A feature frame with stress, RR and the band's HRV all present, to see what a revocation takes away. */
    private fun fullFeatureSidecar(): ByteArray =
        encodeSynapseSidecarRecord(
            1_000_000_000L,
            ByteArray(27).also { f ->
                f[0] = 2
                f[8] = 60
                f[10] = 0xE8.toByte()
                f[11] = 0x03
                f[12] = 1
                f[16] = 1
                f[20] = 40
                f[22] = 50
                f[24] = 0xFF.toByte()
                f[26] = 0xFF.toByte()
            },
        )

    private fun app(
        quotaBytes: Long = 1L shl 30,
        block: suspend HttpClient.() -> Unit,
    ) = testApplication {
        application {
            content()
            auth(idp.config, idp.jwks)
            val userData = UserData(db, raw)
            val store = SynapseStore(db, raw)
            val revoke = revocationHook(userData, store)
            val consentStore = consent(db) { owner, scope -> revoke(owner, scope) }
            activities(db, raw, quotaBytes = quotaBytes, consentStore = consentStore, userData = userData)
            synapse(db, raw, consentStore, quotaBytes = quotaBytes, store = store)
        }
        client.block()
    }

    /** An activity of the caller's, recorded by the real recorder and uploaded, as a device would. */
    private suspend fun HttpClient.activity(token: String = alice): UUID {
        val id = UUID.randomUUID()
        val recorder = ActivityRecorder(ActivityStore(Path(dir.toString())))
        recorder.start(id.toString(), Sport.RUN, 0, 1_700_000_000_000)
        recorder.gpsReady(0, 1_700_000_000_000)
        val step = 100.0 / 111_195.08
        for (i in 0..5) {
            val t = i * 10_000L
            recorder.onSample(LocationSample(1_700_000_000_000 + t, t, i * step, 8.0, 5.0))
        }
        recorder.stop(60_000, 1_700_000_060_000)
        val bytes = java.io.File(dir.toFile(), "$id.jsonl").readBytes()
        val response =
            put("/v1/activities/$id") {
                bearerAuth(token)
                setBody(bytes)
            }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return id
    }

    private suspend fun HttpClient.grant(
        scope: String,
        token: String = alice,
    ) {
        val r = post("/v1/consents/$scope") { bearerAuth(token) }
        assertTrue(r.status.value in 200..204, "grant $scope: ${r.status}")
    }

    private suspend fun HttpClient.revoke(
        scope: String,
        token: String = alice,
    ) {
        assertEquals(HttpStatusCode.NoContent, delete("/v1/consents/$scope") { bearerAuth(token) }.status)
    }

    private suspend fun HttpClient.putSidecar(
        id: UUID,
        kind: String,
        bytes: ByteArray,
        token: String = alice,
    ): HttpResponse =
        put("/v1/activities/$id/synapse/$kind") {
            bearerAuth(token)
            setBody(bytes)
        }

    private suspend fun HttpClient.summary(
        id: UUID,
        token: String = alice,
    ): SynapseSummaryDto {
        val r = get("/v1/activities/$id/synapse") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return json.decodeFromString(SynapseSummaryDto.serializer(), r.bodyAsText())
    }

    @Test
    fun aSidecarNeedsItsOwnConsent() =
        app {
            val id = activity()

            assertEquals(HttpStatusCode.Forbidden, putSidecar(id, "features", featureSidecar).status)
            assertEquals(HttpStatusCode.Forbidden, putSidecar(id, "raw", rawSidecar).status)
            grant(ConsentScope.SIGNAL_QUALITY)
            assertEquals(HttpStatusCode.NoContent, putSidecar(id, "features", featureSidecar).status)
            assertEquals(
                HttpStatusCode.Forbidden,
                putSidecar(id, "raw", rawSidecar).status,
                "signal_quality never implies raw_signals",
            )
        }

    @Test
    fun theSummaryShowsWhatWasRecordedAndOnlyWhatTheConsentsAllow() =
        app {
            val id = activity()
            grant(ConsentScope.SIGNAL_QUALITY)
            grant(ConsentScope.RAW_SIGNALS)
            assertEquals(HttpStatusCode.NoContent, putSidecar(id, "features", featureSidecar).status)
            assertEquals(HttpStatusCode.NoContent, putSidecar(id, "raw", rawSidecar).status)

            val s = summary(id)

            assertEquals("synapse-device", s.source)
            assertEquals("synapse-series-v1", s.seriesVersion)
            val features = assertNotNull(s.features)
            assertEquals(59, features.frames)
            assertEquals(3, features.lostFrames)
            assertEquals(1, features.unknownVersionFrames)
            assertTrue(features.stress.all { it == null }, "no stress_monitoring consent")
            val streams = assertNotNull(s.raw).streams.associateBy { it.stream }
            assertEquals(463, streams.getValue("ecg").frames)
            assertEquals(4, streams.getValue("ecg").lostFrames)
            assertEquals(mapOf("unknown_version" to 1), s.raw!!.invalidFrames)
        }

    @Test
    fun aWindowOfASignalIsBoundedAndInTheContractsUnit() =
        app {
            val id = activity()
            grant(ConsentScope.RAW_SIGNALS)
            assertEquals(HttpStatusCode.NoContent, putSidecar(id, "raw", rawSidecar).status)

            val r = get("/v1/activities/$id/synapse/raw/ecg?from=0&to=61000&maxPoints=200") { bearerAuth(alice) }
            assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
            val w = json.decodeFromString(SynapseWindowDto.serializer(), r.bodyAsText())

            assertEquals("ecg", w.stream)
            assertEquals(listOf("uV"), w.channels.map { it.unit })
            assertEquals(3, w.segments.size, "a restart and a hole make three runs, never one line across them")
            assertTrue(w.segments.all { it.min[0].size <= 200 })
            assertTrue(w.segments.maxOf { it.max[0].max() } >= 900.0, "the R wave survives the decimation")
            assertEquals(
                HttpStatusCode.NotFound,
                get("/v1/activities/$id/synapse/raw/power") { bearerAuth(alice) }.status,
                "a stream that was not recorded",
            )
            assertEquals(
                HttpStatusCode.BadRequest,
                get("/v1/activities/$id/synapse/raw/ecg?from=10&to=10") { bearerAuth(alice) }.status,
            )
        }

    @Test
    fun revokingRawSignalsDeletesTheFileLeavesATombstoneAndKeepsTheFeatures() =
        app {
            val id = activity()
            grant(ConsentScope.SIGNAL_QUALITY)
            grant(ConsentScope.RAW_SIGNALS)
            putSidecar(id, "features", featureSidecar)
            putSidecar(id, "raw", rawSidecar)
            val key = SynapseStore(db, raw).get(owner("alice"), id, SynapseKind.RAW)!!.objectKey
            assertTrue(raw.exists(key))

            revoke(ConsentScope.RAW_SIGNALS)

            assertFalse(raw.exists(key), "revoking really deletes the data")
            val s = summary(id)
            assertNull(s.raw)
            assertNotNull(s.features)
            grant(ConsentScope.RAW_SIGNALS)
            assertEquals(
                HttpStatusCode.Gone,
                putSidecar(id, "raw", rawSidecar).status,
                "a device that still has it cannot upload it again",
            )
        }

    @Test
    fun revokingStressMonitoringMasksOnlyTheStressOfTheStoredFrames() =
        app {
            val id = activity()
            grant(ConsentScope.SIGNAL_QUALITY)
            grant(ConsentScope.STRESS_MONITORING)
            grant(ConsentScope.HRV_ANALYSIS)
            putSidecar(id, "features", fullFeatureSidecar())
            assertEquals(listOf("stress"), summary(id).features!!.stress)

            revoke(ConsentScope.STRESS_MONITORING)

            val stored = SynapseStore(db, raw).get(owner("alice"), id, SynapseKind.FEATURES)!!
            val frame = readSynapseSidecar(raw.get(stored.objectKey)).records.single().frame
            val f = (parseSynapseFeatureFrame(frame) as dev.averyn.sensors.SynapseFrameResult.Frame).frame
            assertNull(f.stress, "the stress byte of the stored file is gone, not just hidden")
            assertEquals(60, f.heartRateBpm)
            assertEquals(40, f.rmssdMs, "the band's HRV is covered by hrv_analysis, still granted")
            assertEquals(listOf(null), summary(id).features!!.stress)
        }

    @Test
    fun revokingSignalQualityDeletesTheFeatureSidecar() =
        app {
            val id = activity()
            grant(ConsentScope.SIGNAL_QUALITY)
            putSidecar(id, "features", featureSidecar)

            revoke(ConsentScope.SIGNAL_QUALITY)

            assertNull(summary(id).features)
            grant(ConsentScope.SIGNAL_QUALITY)
            assertEquals(HttpStatusCode.Gone, putSidecar(id, "features", featureSidecar).status)
        }

    @Test
    fun anotherUserSeesNothingAndCannotAttach() =
        app {
            val id = activity()
            grant(ConsentScope.SIGNAL_QUALITY)
            putSidecar(id, "features", featureSidecar)
            grant(ConsentScope.SIGNAL_QUALITY, token = bob)

            assertEquals(HttpStatusCode.NotFound, get("/v1/activities/$id/synapse") { bearerAuth(bob) }.status)
            assertEquals(HttpStatusCode.NotFound, putSidecar(id, "features", featureSidecar, token = bob).status)
            assertEquals(HttpStatusCode.Unauthorized, get("/v1/activities/$id/synapse").status)
        }

    @Test
    fun uploadsAreValidatedAndSizedAgainstTheQuota() =
        app(quotaBytes = 4_096) {
            val id = activity()
            grant(ConsentScope.RAW_SIGNALS)
            grant(ConsentScope.SIGNAL_QUALITY)

            assertEquals(HttpStatusCode.NotFound, putSidecar(id, "nothing", rawSidecar).status)
            assertEquals(
                HttpStatusCode.NotFound,
                putSidecar(UUID.randomUUID(), "raw", rawSidecar).status,
                "no such activity",
            )
            assertEquals(HttpStatusCode.UnprocessableEntity, putSidecar(id, "raw", byteArrayOf(1, 2, 3)).status)
            assertEquals(
                HttpStatusCode.InsufficientStorage,
                putSidecar(id, "raw", rawSidecar).status,
                "150 kB over a 4 kB quota",
            )
        }

    @Test
    fun deletingTheActivityTakesItsSidecarsWithIt() =
        app {
            val id = activity()
            grant(ConsentScope.RAW_SIGNALS)
            putSidecar(id, "raw", rawSidecar)
            val key = SynapseStore(db, raw).get(owner("alice"), id, SynapseKind.RAW)!!.objectKey

            // the delete route takes the server's id of the activity, not the id the device gave it
            val serverId = SynapseStore(db, raw).activityId(owner("alice"), id)
            assertEquals(HttpStatusCode.NoContent, delete("/v1/activities/$serverId") { bearerAuth(alice) }.status)

            assertFalse(raw.exists(key))
            val rows =
                db.connection.use { c ->
                    c.createStatement().executeQuery("SELECT count(*) FROM activity_synapse_sidecars").use {
                        it.next()
                        it.getLong(1)
                    }
                }
            assertEquals(0L, rows)
        }

    private fun owner(subject: String): UUID =
        db.connection.use { c ->
            c.prepareStatement("SELECT id FROM users WHERE oidc_subject = ?").use { st ->
                st.setString(1, subject)
                st.executeQuery().use { rs ->
                    check(rs.next()) { "no user $subject yet" }
                    rs.getObject(1, UUID::class.java)
                }
            }
        }

    @Test
    fun everyScopeTheServerAcceptsHasARevocationRule() {
        val hook = revocationHook(UserData(db, raw), SynapseStore(db, raw))
        val owner = UUID.randomUUID()
        db.connection.use { c ->
            c.prepareStatement("INSERT INTO users (id, oidc_issuer, oidc_subject) VALUES (?, 'test', ?)").use {
                it.setObject(1, owner)
                it.setString(2, "scope-check")
                it.executeUpdate()
            }
        }
        for (scope in ConsentScope.ALL) hook(owner, scope) // none of them may be an unknown scope
        assertTrue(runCatching { hook(owner, "not_a_scope") }.isFailure, "an unhandled scope fails loudly")
    }
}
