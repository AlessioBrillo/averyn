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
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The personal records of an account, end to end (TDD-0010): the best efforts are computed with the analysis,
 * the records are the fastest of them, and they are only for their owner. Real PostgreSQL/PostGIS and a real
 * S3 API (SeaweedFS, as in Compose) via Testcontainers.
 */
class PersonalRecordsTest {
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
        dir = Files.createTempDirectory("averyn-records-test")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
        db.connection.use { it.createStatement().execute("TRUNCATE activities, users CASCADE") }
    }

    /**
     * A straight run of [steps] samples [stepM] metres apart, one every [stepMs] milliseconds: 1 100 m in
     * twelve samples by default. A wide accuracy makes the quality of the activity `POOR`.
     */
    private fun record(
        steps: Int = 12,
        stepM: Double = 100.0,
        stepMs: Long = 10_000,
        accuracyM: Double = 5.0,
    ): Pair<UUID, ByteArray> {
        val id = UUID.randomUUID()
        val recorder = ActivityRecorder(ActivityStore(Path(dir.toString())))
        recorder.start(id.toString(), Sport.RUN, 0, 1_700_000_000_000)
        recorder.gpsReady(0, 1_700_000_000_000)
        val step = stepM / 111_195.08
        for (i in 0 until steps) {
            val t = i * stepMs
            recorder.onSample(
                LocationSample(
                    1_700_000_000_000 + t,
                    t,
                    i * step,
                    8.0,
                    accuracyM,
                ),
            )
        }
        val end = steps * stepMs
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

    private fun count(sql: String): Long =
        db.connection.use { conn ->
            conn.createStatement().executeQuery(sql).use { rs -> if (rs.next()) rs.getLong(1) else 0 }
        }

    private suspend fun HttpClient.records(token: String = alice): PersonalRecords =
        json.decodeFromString(
            PersonalRecords.serializer(),
            get("/v1/me/records") { bearerAuth(token) }.bodyAsText(),
        )

    @Test
    fun theBestEffortsAreComputedWhenTheActivityIsStored() {
        app {
            val (id, bytes) = record()
            assertEquals(HttpStatusCode.Created, upload(id, bytes).status)

            assertEquals(1L, count("SELECT count(*) FROM activity_best_efforts"))
            assertEquals(
                "best-efforts-v1",
                scalar("SELECT best_efforts_version FROM activity_analyses"),
            )
        }
    }

    @Test
    fun theAnalysisOfTheActivityCarriesItsBestEfforts() {
        app {
            val (id, bytes) = record()
            val summary = json.decodeFromString(ActivitySummary.serializer(), upload(id, bytes).bodyAsText())

            val analysis =
                json.decodeFromString(
                    ActivityAnalysis.serializer(),
                    get("/v1/activities/${summary.id}/analysis") { bearerAuth(alice) }.bodyAsText(),
                )

            assertEquals("best-efforts-v1", analysis.algorithmVersions.bestEfforts)
            val effort = analysis.bestEfforts.single()
            assertEquals(1_000.0, effort.distanceM)
            // A hundred metres every ten seconds: a kilometre takes a hundred seconds.
            assertEquals(100_000, effort.durationMs)
            assertEquals(0.0, effort.startDistanceM, 0.01)
            assertEquals(1_000.0, effort.endDistanceM, 0.01)
            assertEquals(10.0, effort.avgSpeedMps, 0.01)
        }
    }

    @Test
    fun theRecordsAreTheFastestEffortOfTheAccount() {
        app {
            val (slowId, slowBytes) = record(stepMs = 12_000)
            val (fastId, fastBytes) = record(stepMs = 10_000)
            upload(slowId, slowBytes)
            val fast = json.decodeFromString(ActivitySummary.serializer(), upload(fastId, fastBytes).bodyAsText())

            val records = records()

            assertEquals(0, records.pendingActivities)
            val run = records.sports.single { it.sport == "RUN" }
            val oneKm = run.targets.single { it.distanceM == 1_000.0 }
            val record = assertNotNull(oneKm.record)
            // The faster activity holds the record.
            assertEquals(fast.id, record.activityId)
            assertEquals(100_000, record.durationMs)
            assertEquals("device", record.source)
            assertEquals("GOOD", record.qualityGrade)
            // The targets the account has not reached yet are there, without a record.
            assertNull(run.targets.single { it.distanceM == 5_000.0 }.record)
        }
    }

    @Test
    fun theRecordsAreOnlyForTheCaller() {
        app {
            val (id, bytes) = record()
            upload(id, bytes)
            assertTrue(records().sports.isNotEmpty())

            // Another user has no activity, and no record: the same answer as an empty account.
            assertTrue(records(bob).sports.isEmpty())
            assertEquals(HttpStatusCode.Unauthorized, get("/v1/me/records").status)
        }
    }

    @Test
    fun anActivityOfPoorQualityDoesNotHoldARecord() {
        app {
            val (id, bytes) = record(accuracyM = 100.0)
            upload(id, bytes)

            // The platform says the distance of such an activity is not reliable: it does not set a record.
            assertEquals("POOR", scalar("SELECT quality_grade FROM activities"))
            assertTrue(records().sports.isEmpty())
        }
    }

    @Test
    fun deletingTheActivityThatHoldsTheRecordPromotesTheNextOne() {
        app {
            val (slowId, slowBytes) = record(stepMs = 12_000)
            val (fastId, fastBytes) = record(stepMs = 10_000)
            upload(slowId, slowBytes)
            val fast = json.decodeFromString(ActivitySummary.serializer(), upload(fastId, fastBytes).bodyAsText())

            assertEquals(
                fast.id,
                records()
                    .sports
                    .single()
                    .targets
                    .single { it.distanceM == 1_000.0 }
                    .record
                    ?.activityId,
            )

            assertEquals(HttpStatusCode.NoContent, delete("/v1/activities/${fast.id}") { bearerAuth(alice) }.status)

            // The next activity takes the record: the records are a projection, not a thing to keep in step.
            val record =
                assertNotNull(
                    records()
                        .sports
                        .single()
                        .targets
                        .single { it.distanceM == 1_000.0 }
                        .record,
                )
            assertEquals(120_000, record.durationMs)
        }
    }

    @Test
    fun anActivityStoredBeforeTheBestEffortsExistedIsAnalysedWhenItIsRead() {
        app {
            val (id, bytes) = record()
            val summary = json.decodeFromString(ActivitySummary.serializer(), upload(id, bytes).bodyAsText())
            // The analysis of an activity stored before the best efforts existed.
            db.connection.use {
                it.createStatement().execute(
                    "UPDATE activity_analyses SET best_efforts_version = 'none'",
                )
            }
            db.connection.use { it.createStatement().execute("DELETE FROM activity_best_efforts") }

            // The records are incomplete while the activity has not been read.
            assertEquals(1, records().pendingActivities)

            get("/v1/activities/${summary.id}/analysis") { bearerAuth(alice) }

            assertEquals(0, records().pendingActivities)
            assertEquals(1L, count("SELECT count(*) FROM activity_best_efforts"))
        }
    }

    @Test
    fun theExportHoldsTheRecords() {
        app {
            val (id, bytes) = record()
            upload(id, bytes)

            val exported = unzip(get("/v1/me/export") { bearerAuth(alice) }.readRawBytes())

            val records =
                json.decodeFromString(
                    PersonalRecords.serializer(),
                    exported.getValue("records.json").decodeToString(),
                )
            assertEquals("best-efforts-v1", records.algorithmVersion)
            assertEquals(
                100_000,
                records.sports
                    .single()
                    .targets
                    .single { it.distanceM == 1_000.0 }
                    .record
                    ?.durationMs,
            )
        }
    }

    private fun scalar(sql: String): Any? =
        db.connection.use { conn ->
            conn.createStatement().executeQuery(sql).use { rs -> if (rs.next()) rs.getObject(1) else null }
        }

    /** The entries of the export, by name. */
    private fun unzip(bytes: ByteArray): Map<String, ByteArray> =
        ZipInputStream(bytes.inputStream()).use { zip ->
            buildMap {
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) put(entry.name, zip.readBytes())
                    entry = zip.nextEntry
                }
            }
        }
}
