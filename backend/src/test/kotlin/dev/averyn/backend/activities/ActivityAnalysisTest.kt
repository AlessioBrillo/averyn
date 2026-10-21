package dev.averyn.backend.activities

import dev.averyn.analysis.ActivityAnalysis
import dev.averyn.backend.TestStack
import dev.averyn.backend.auth.TestIssuer
import dev.averyn.backend.auth.auth
import dev.averyn.backend.content
import dev.averyn.domain.LocationSample
import dev.averyn.domain.Sport
import dev.averyn.tracking.ActivityRecorder
import dev.averyn.tracking.ActivityStore
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The analysis of an activity, end to end (TDD-0009): the splits, the elevation and the series are computed when
 * the activity is stored, served to their owner only, recomputed when they are missing and deleted with the
 * activity. Real PostgreSQL/PostGIS and a real S3 API (SeaweedFS, as in Compose) via Testcontainers.
 */
class ActivityAnalysisTest {
    /**
     * The stack is shared by the whole suite ([TestStack]): the tables are truncated after every test, and the
     * objects live under the prefix of the user of the test.
     */
    private val db get() = TestStack.db
    private val raw get() = TestStack.raw

    private val idp = TestIssuer()
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var dir: java.nio.file.Path
    private val alice get() = idp.token(subject = "alice")
    private val bob get() = idp.token(subject = "bob")

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("averyn-analysis-test")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
        db.connection.use { it.createStatement().execute("TRUNCATE activities, users CASCADE") }
    }

    /** A straight run of [steps] samples 100 m apart, one every ten seconds: 1 100 m over two minutes. */
    private fun record(
        steps: Int = 12,
        withAltitude: Boolean = true,
    ): Pair<UUID, ByteArray> {
        val id = UUID.randomUUID()
        val recorder = ActivityRecorder(ActivityStore(Path(dir.toString())))
        recorder.start(id.toString(), Sport.RUN, 0, 1_700_000_000_000)
        recorder.gpsReady(0, 1_700_000_000_000)
        val step = 100.0 / 111_195.08
        for (i in 0 until steps) {
            val t = i * 10_000L
            recorder.onSample(
                LocationSample(
                    1_700_000_000_000 + t,
                    t,
                    i * step,
                    8.0,
                    5.0,
                    altitudeM = if (withAltitude) 100.0 + i else null,
                ),
            )
        }
        val end = steps * 10_000L
        recorder.stop(end, 1_700_000_000_000 + end)
        return id to File(dir.toFile(), "$id.jsonl").readBytes()
    }

    private fun app(block: suspend HttpClient.() -> Unit) =
        testApplication {
            application {
                content()
                auth(idp.config, idp.jwks)
                activities(db, raw)
            }
            client.block()
        }

    private suspend fun HttpClient.upload(
        id: UUID,
        bytes: ByteArray,
    ): HttpResponse =
        put("/v1/activities/$id") {
            bearerAuth(alice)
            setBody(bytes)
        }

    private fun scalar(sql: String): Any? =
        db.connection.use { conn ->
            conn.createStatement().executeQuery(sql).use { rs -> if (rs.next()) rs.getObject(1) else null }
        }

    private fun count(sql: String): Long =
        db.connection.use { conn ->
            conn.createStatement().executeQuery(sql).use { rs -> if (rs.next()) rs.getLong(1) else 0 }
        }

    @Test
    fun theAnalysisIsComputedWhenTheActivityIsStored() {
        app {
            val (id, bytes) = record()
            assertEquals(HttpStatusCode.Created, upload(id, bytes).status)

            assertEquals(1L, count("SELECT count(*) FROM activity_analyses"))
            assertEquals(2L, count("SELECT count(*) FROM activity_splits"))
            val stored = scalar("SELECT series FROM activity_analyses")
            assertNotNull(stored)
            assertTrue(stored.toString().contains("\"distanceM\""))
        }
    }

    @Test
    fun theAnalysisHasTheSplitsThatAddUpToTheTrackAndTheTotalsOfTheActivity() {
        app {
            val (id, bytes) = record()
            val created = upload(id, bytes)
            val summary = json.decodeFromString(ActivitySummary.serializer(), created.bodyAsText())

            val response = get("/v1/activities/${summary.id}/analysis") { bearerAuth(alice) }
            assertEquals(HttpStatusCode.OK, response.status)
            val analysis = json.decodeFromString(ActivityAnalysis.serializer(), response.bodyAsText())

            assertEquals("splits-v1", analysis.algorithmVersions.splits)
            assertEquals("elevation-v1", analysis.algorithmVersions.elevation)
            assertEquals("hr-v2", analysis.algorithmVersions.heartRate)
            assertEquals(1_000.0, analysis.splitLengthM)
            // One full kilometer and the 100 m that were left.
            assertEquals(2, analysis.splits.size)
            assertEquals(1_000.0, analysis.splits[0].endDistanceM, 0.01)
            assertTrue(!analysis.splits[0].partial)
            assertTrue(analysis.splits[1].partial)
            // The splits cover the whole track.
            assertEquals(
                summary.distanceM,
                analysis.splits.sumOf { it.endDistanceM - it.startDistanceM },
                0.01,
            )
            // And the totals are the ones of the summary.
            assertEquals(summary.distanceM, analysis.totals.distanceM, 0.001)
            assertEquals(summary.elapsedMs, analysis.totals.elapsedMs)
            assertEquals(summary.movingMs, analysis.totals.movingMs)
            assertEquals(summary.stationaryMs, analysis.totals.stationaryMs)
            // The altitude of the samples is measured, so the elevation is there.
            val elevation = assertNotNull(analysis.elevation)
            assertEquals("recorded", elevation.source)
            // No heart-rate strap, no heart rate: not a heart rate of zero.
            assertNull(analysis.heartRate)
            assertTrue(
                analysis.series.heartRate.bpm
                    .isEmpty(),
            )
            // The series has one point per sample, and the pause of the file is a segment of its own.
            assertEquals(12, analysis.series.distanceM.size)
        }
    }

    @Test
    fun anActivityWithoutAltitudeHasNoElevationAndTheSeriesKeepsTheHoles() =
        app {
            val (id, bytes) = record(withAltitude = false)
            val created = upload(id, bytes)
            val summary = json.decodeFromString(ActivitySummary.serializer(), created.bodyAsText())

            val response = get("/v1/activities/${summary.id}/analysis") { bearerAuth(alice) }
            assertEquals(HttpStatusCode.OK, response.status)
            val analysis = json.decodeFromString(ActivityAnalysis.serializer(), response.bodyAsText())

            // No altitude is not a flat activity: there is no elevation at all.
            assertNull(analysis.elevation)
            assertTrue(analysis.splits.isNotEmpty())
            assertNull(analysis.splits.first().elevationGainM)
            // The holes of the series survive the round trip through the database.
            assertTrue(analysis.series.elevationM.all { it == null })
            assertEquals(analysis.series.distanceM.size, analysis.series.elevationM.size)
        }

    @Test
    fun theAnalysisIsOnlyForItsOwner() {
        app {
            val (id, bytes) = record()
            val created = upload(id, bytes)
            val summary = json.decodeFromString(ActivitySummary.serializer(), created.bodyAsText())

            assertEquals(HttpStatusCode.OK, get("/v1/activities/${summary.id}/analysis") { bearerAuth(alice) }.status)
            // IDOR (threat model): another user, and a nonexistent id, get the same 404.
            assertEquals(
                HttpStatusCode.NotFound,
                get("/v1/activities/${summary.id}/analysis") { bearerAuth(bob) }.status,
            )
            assertEquals(
                HttpStatusCode.NotFound,
                get("/v1/activities/${UUID.randomUUID()}/analysis") { bearerAuth(alice) }.status,
            )
            assertEquals(HttpStatusCode.BadRequest, get("/v1/activities/nope/analysis") { bearerAuth(alice) }.status)
            assertEquals(HttpStatusCode.Unauthorized, get("/v1/activities/${summary.id}/analysis").status)
        }
    }

    @Test
    fun anAnalysisThatIsMissingIsComputedWhenItIsRead() {
        app {
            val (id, bytes) = record()
            val created = upload(id, bytes)
            val summary = json.decodeFromString(ActivitySummary.serializer(), created.bodyAsText())
            // The analysis of an activity stored before the analysis existed.
            db.connection.use { it.createStatement().execute("DELETE FROM activity_analyses") }
            db.connection.use { it.createStatement().execute("DELETE FROM activity_splits") }

            val response = get("/v1/activities/${summary.id}/analysis") { bearerAuth(alice) }

            assertEquals(HttpStatusCode.OK, response.status)
            val analysis = json.decodeFromString(ActivityAnalysis.serializer(), response.bodyAsText())
            assertEquals(2, analysis.splits.size)
            // And it is stored, so the next read is cheap.
            assertEquals(1L, count("SELECT count(*) FROM activity_analyses"))
            assertEquals(2L, count("SELECT count(*) FROM activity_splits"))
        }
    }

    @Test
    fun anAnalysisThatWasComputedWithAnotherVersionIsComputedAgain() {
        app {
            val (id, bytes) = record()
            val created = upload(id, bytes)
            val summary = json.decodeFromString(ActivitySummary.serializer(), created.bodyAsText())
            db.connection.use {
                it.createStatement().execute("UPDATE activity_analyses SET splits_version = 'splits-v0'")
            }

            val response = get("/v1/activities/${summary.id}/analysis") { bearerAuth(alice) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("splits-v1", scalar("SELECT splits_version FROM activity_analyses"))
        }
    }

    @Test
    fun deletingTheActivityDeletesItsAnalysis() {
        app {
            val (id, bytes) = record()
            val created = upload(id, bytes)
            val summary = json.decodeFromString(ActivitySummary.serializer(), created.bodyAsText())
            assertEquals(1L, count("SELECT count(*) FROM activity_analyses"))

            assertEquals(HttpStatusCode.NoContent, delete("/v1/activities/${summary.id}") { bearerAuth(alice) }.status)

            assertEquals(0L, count("SELECT count(*) FROM activity_analyses"))
            assertEquals(0L, count("SELECT count(*) FROM activity_splits"))
            assertEquals(
                HttpStatusCode.NotFound,
                get("/v1/activities/${summary.id}/analysis") { bearerAuth(alice) }.status,
            )
        }
    }

    @Test
    fun deletingTheAccountDeletesItsAnalyses() {
        app {
            val (id, bytes) = record()
            upload(id, bytes)
            assertEquals(1L, count("SELECT count(*) FROM activity_analyses"))

            assertEquals(HttpStatusCode.NoContent, delete("/v1/me") { bearerAuth(alice) }.status)

            assertEquals(0L, count("SELECT count(*) FROM activity_analyses"))
            assertEquals(0L, count("SELECT count(*) FROM activity_splits"))
        }
    }
}
