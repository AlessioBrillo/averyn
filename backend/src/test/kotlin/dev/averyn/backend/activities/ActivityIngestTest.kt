package dev.averyn.backend.activities

import com.auth0.jwt.JWT
import dev.averyn.backend.auth.TestIssuer
import dev.averyn.backend.auth.auth
import dev.averyn.backend.auth.userIdFor
import dev.averyn.backend.connectAndMigrate
import dev.averyn.backend.content
import dev.averyn.backend.storage.RawObjectStore
import dev.averyn.domain.LocationSample
import dev.averyn.domain.Reason
import dev.averyn.domain.Sport
import dev.averyn.tracking.ActivityRecorder
import dev.averyn.tracking.ActivityStore
import dev.averyn.tracking.CompletedActivity
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real PostgreSQL/PostGIS and a real S3 API (SeaweedFS, as in Compose) via Testcontainers: Docker is required. */
class ActivityIngestTest {
    private companion object {
        val postgres: PostgreSQLContainer =
            PostgreSQLContainer(
                DockerImageName.parse("postgis/postgis:18-3.6-alpine").asCompatibleSubstituteFor("postgres"),
            ).withDatabaseName("averyn")
                .also { it.start() }

        val seaweed: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("chrislusf/seaweedfs:4.47"))
                .withCommand("server", "-dir=/data", "-s3", "-s3.config=/etc/seaweedfs/s3.json")
                .withCopyToContainer(Transferable.of(S3_CONFIG), "/etc/seaweedfs/s3.json")
                .withExposedPorts(8333)
                .waitingFor(Wait.forListeningPort())
                .also { it.start() }

        const val S3_CONFIG =
            """{"identities":[{"name":"test","credentials":[{"accessKey":"test","secretKey":"test"}],"actions":["Admin","Read","Write","List","Tagging"]}]}"""

        val endpoint = "http://${seaweed.host}:${seaweed.getMappedPort(8333)}"
        val bucket = "averyn-test"
        val db = connectAndMigrate(postgres.jdbcUrl, postgres.username, postgres.password)
        val raw = RawObjectStore(endpoint, bucket, "test", "test")

        init {
            raw.ensureBucket() // retries while the object store comes up
        }
    }

    private val idp = TestIssuer()
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var dir: java.nio.file.Path
    private val alice get() = idp.token(subject = "alice")
    private val bob get() = idp.token(subject = "bob")

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("averyn-ingest-test")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
        db.connection.use { it.createStatement().execute("TRUNCATE activities, users CASCADE") }
    }

    private class Recorded(
        val id: UUID,
        val bytes: ByteArray,
        val live: CompletedActivity?,
    )

    /** What a device produces: a straight 600 m run with a pause, written by the real recorder and store. */
    private fun record(
        finish: Boolean = true,
        fail: Boolean = false,
    ): Recorded {
        val id = UUID.randomUUID()
        val recorder = ActivityRecorder(ActivityStore(Path(dir.toString())))
        recorder.start(id.toString(), Sport.RUN, 0, 1_700_000_000_000)
        recorder.gpsReady(0, 1_700_000_000_000)
        val step = 100.0 / 111_195.08
        for (i in 0..5) {
            val t = i * 10_000L
            recorder.onSample(LocationSample(1_700_000_000_000 + t, t, i * step, 8.0, 5.0))
            if (i == 2) {
                recorder.pause(t, 1_700_000_000_000 + t)
                recorder.resume(t + 5_000, 1_700_000_005_000 + t)
            }
        }
        var live: CompletedActivity? = null
        if (fail) recorder.fail(60_000, 1_700_000_060_000, Reason.PERMISSION_REVOKED)
        if (finish) live = recorder.stop(60_000, 1_700_000_060_000)
        return Recorded(id, File(dir.toFile(), "$id.jsonl").readBytes(), live)
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it)
        }

    private fun app(
        maxBytes: Long = MAX_ACTIVITY_BYTES,
        uploadSlots: Semaphore = Semaphore(MAX_CONCURRENT_UPLOADS),
        exportSlots: Semaphore = Semaphore(MAX_CONCURRENT_EXPORTS),
        block: suspend HttpClient.() -> Unit,
    ) = testApplication {
        application {
            content()
            auth(idp.config, idp.jwks)
            activities(db, raw, maxBytes, uploadSlots, exportSlots)
        }
        client.block()
    }

    private suspend fun HttpClient.upload(
        id: UUID,
        bytes: ByteArray,
        token: String? = alice,
    ): HttpResponse =
        put("/v1/activities/$id") {
            token?.let { bearerAuth(it) }
            setBody(bytes)
        }

    private suspend fun HttpResponse.summary() = json.decodeFromString(ActivitySummary.serializer(), bodyAsText())

    private fun scalar(sql: String): Any? =
        db.connection.use { conn ->
            conn.createStatement().executeQuery(sql).use { rs -> if (rs.next()) rs.getObject(1) else null }
        }

    @Test
    fun uploadStoresTheRawFileAndRecomputesTheSameNumbersAsTheDevice() =
        app {
            val rec = record()
            val response = upload(rec.id, rec.bytes)

            assertEquals(HttpStatusCode.Created, response.status)
            val summary = response.summary()
            val live = checkNotNull(rec.live)
            assertEquals(live.metrics.distanceM, summary.distanceM)
            assertEquals(live.metrics.elapsedMs, summary.elapsedMs)
            assertEquals(live.metrics.movingMs, summary.movingMs)
            assertEquals(live.metrics.pausedMs, summary.pausedMs)
            assertEquals(live.metrics.averageSpeedMps, summary.averageSpeedMps)
            assertEquals(live.metrics.paceSecPerKm, summary.paceSecPerKm)
            assertEquals(live.quality.grade.name, summary.qualityGrade)
            assertEquals("COMPLETED", summary.finalState)
            assertEquals("RUN", summary.sport)
            assertEquals("private", summary.visibility)
            assertEquals(0, summary.droppedRecords)
            assertEquals(rec.id.toString(), summary.clientActivityId)

            val key = scalar("SELECT raw_object_key FROM activities") as String
            assertContentEquals(rec.bytes, raw.get(key)) // byte for byte (ADR-0016)
            assertEquals(sha256(rec.bytes), scalar("SELECT raw_sha256 FROM activities"))
            assertEquals(6, scalar("SELECT ST_NPoints(track) FROM activities"))
        }

    @Test
    fun uploadingTheSameBytesAgainIsIdempotent() =
        app {
            val rec = record()
            val first = upload(rec.id, rec.bytes)
            val second = upload(rec.id, rec.bytes)

            assertEquals(HttpStatusCode.Created, first.status)
            assertEquals(HttpStatusCode.OK, second.status)
            assertEquals(first.summary().id, second.summary().id)
            assertEquals(1L, scalar("SELECT count(*) FROM activities"))
        }

    @Test
    fun theSameIdWithDifferentBytesIsAConflictAndTheFirstUploadWins() =
        app {
            val rec = record()
            upload(rec.id, rec.bytes)
            val extraEvent = """EVENT {"elapsedRealtimeMs":1,"timeMs":1,"state":"COMPLETED","reason":"USER"}"""
            val changed = rec.bytes + "$extraEvent\n".toByteArray()

            assertEquals(HttpStatusCode.Conflict, upload(rec.id, changed).status)
            assertEquals(sha256(rec.bytes), scalar("SELECT raw_sha256 FROM activities"))
            assertEquals(1L, scalar("SELECT count(*) FROM activities"))
        }

    @Test
    fun anActivityThatIsNotFinishedIsRejected() =
        app {
            val rec = record(finish = false)
            assertEquals(HttpStatusCode.UnprocessableEntity, upload(rec.id, rec.bytes).status)
            assertEquals(0L, scalar("SELECT count(*) FROM activities"))
        }

    @Test
    fun aFailedActivityKeepsItsDataAndIsAccepted() =
        app {
            val rec = record(finish = false, fail = true)
            val response = upload(rec.id, rec.bytes)
            assertEquals(HttpStatusCode.Created, response.status)
            assertEquals("FAILED", response.summary().finalState)
        }

    @Test
    fun unreadableUploadsAreRejected() =
        app {
            val rec = record()
            assertEquals(
                HttpStatusCode.UnprocessableEntity,
                upload(rec.id, "not an activity file\n".toByteArray()).status,
            )
            assertEquals(HttpStatusCode.UnprocessableEntity, upload(rec.id, ByteArray(0)).status)
            // A line that parses but breaks the model (latitude 123) is an invalid upload, not a server error.
            val badLatitude = String(rec.bytes).replace("\"latitude\":0.0", "\"latitude\":123.0").toByteArray()
            assertEquals(HttpStatusCode.UnprocessableEntity, upload(rec.id, badLatitude).status)
            // A metadata line that is not a valid activity header.
            assertEquals(
                HttpStatusCode.UnprocessableEntity,
                upload(rec.id, "META {\"activityId\":12}\n".toByteArray()).status,
            )
            // The file's own activity id must match the one in the URL.
            assertEquals(HttpStatusCode.UnprocessableEntity, upload(UUID.randomUUID(), rec.bytes).status)
            assertEquals(0L, scalar("SELECT count(*) FROM activities"))
        }

    @Test
    fun aTruncatedLastLineIsCountedNotSilentlyDropped() =
        app {
            val rec = record()
            val response = upload(rec.id, rec.bytes + "SAMPLE {\"timeMs\":9".toByteArray())
            assertEquals(HttpStatusCode.Created, response.status)
            assertEquals(1, response.summary().droppedRecords)
        }

    @Test
    fun aBodyOverTheLimitIsRejected() =
        app(maxBytes = 1024) {
            val rec = record()
            assertEquals(HttpStatusCode.PayloadTooLarge, upload(rec.id, rec.bytes).status)
            assertEquals(0L, scalar("SELECT count(*) FROM activities"))
        }

    @Test
    fun theLimitAlsoHoldsWithoutAContentLength() =
        app(maxBytes = 1024) {
            val rec = record()
            // A ByteReadChannel body has no known length, so the server must enforce the limit while reading.
            val response =
                put("/v1/activities/${rec.id}") {
                    bearerAuth(alice)
                    setBody(ByteReadChannel(rec.bytes))
                }
            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertEquals(0L, scalar("SELECT count(*) FROM activities"))
        }

    @Test
    fun whenAllUploadSlotsAreTakenTheServerAsksToRetryLater() {
        val slots = Semaphore(1)
        assertTrue(slots.tryAcquire()) // the only slot is busy with another upload
        app(uploadSlots = slots) {
            val rec = record()
            val response = upload(rec.id, rec.bytes)
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("5", response.headers[HttpHeaders.RetryAfter])
            assertEquals(0L, scalar("SELECT count(*) FROM activities"))

            slots.release()
            // The slot is given back after every upload, so the next one fits again.
            assertEquals(HttpStatusCode.Created, upload(rec.id, rec.bytes).status)
            assertEquals(HttpStatusCode.OK, upload(rec.id, rec.bytes).status)
            assertTrue(slots.tryAcquire())
        }
    }

    @Test
    fun aKnownUserIsOnlyReadNeverRewritten() {
        val principal = JWTPrincipal(JWT.decode(alice))
        val first = db.userIdFor(principal)
        val rowVersion = scalar("SELECT xmin::text FROM users")

        assertEquals(first, db.userIdFor(principal))
        assertEquals(rowVersion, scalar("SELECT xmin::text FROM users")) // an UPDATE would have given a new version
        assertEquals(1L, scalar("SELECT count(*) FROM users"))
    }

    @Test
    fun requestsNeedAValidToken() =
        app {
            val rec = record()
            assertEquals(HttpStatusCode.Unauthorized, upload(rec.id, rec.bytes, token = null).status)
            assertEquals(HttpStatusCode.Unauthorized, upload(rec.id, rec.bytes, token = idp.forgedToken()).status)
            assertEquals(HttpStatusCode.Unauthorized, get("/v1/activities/${rec.id}").status)
            assertEquals(HttpStatusCode.BadRequest, put("/v1/activities/nope") { bearerAuth(alice) }.status)
        }

    private suspend fun HttpClient.page(
        query: String,
        token: String = alice,
    ) = get("/v1/activities$query") { bearerAuth(token) }

    @Test
    fun theListIsPagedByKeysetAndOnlyHoldsTheCallersActivities() =
        app {
            // All three share one start time, so the (started_at, id) tie-break is what keeps the pages disjoint.
            val ids =
                List(3) {
                    record().also { rec ->
                        upload(rec.id, rec.bytes, token = alice)
                    }
                }.map { it.id.toString() }
            val bobs = record().also { upload(it.id, it.bytes, token = bob) }

            val first = page("?limit=2")
            assertEquals(HttpStatusCode.OK, first.status)
            val firstPage = json.decodeFromString(ActivityPage.serializer(), first.bodyAsText())
            assertEquals(2, firstPage.items.size)
            val cursor = checkNotNull(firstPage.nextCursor)

            val second = json.decodeFromString(ActivityPage.serializer(), page("?limit=2&cursor=$cursor").bodyAsText())
            assertEquals(1, second.items.size)
            assertEquals(null, second.nextCursor)

            val seen = (firstPage.items + second.items).map { it.clientActivityId }
            assertEquals(ids.sorted(), seen.sorted()) // every activity exactly once, none of Bob's
            assertTrue(bobs.id.toString() !in seen)

            val bobList = json.decodeFromString(ActivityPage.serializer(), page("", token = bob).bodyAsText())
            assertEquals(listOf(bobs.id.toString()), bobList.items.map { it.clientActivityId })
            assertEquals(null, bobList.nextCursor)
        }

    @Test
    fun listParametersAreValidatedAndTheListNeedsAToken() =
        app {
            assertEquals(HttpStatusCode.BadRequest, page("?limit=0").status)
            assertEquals(HttpStatusCode.BadRequest, page("?limit=101").status)
            assertEquals(HttpStatusCode.BadRequest, page("?limit=abc").status)
            assertEquals(HttpStatusCode.BadRequest, page("?cursor=garbage").status)
            assertEquals(HttpStatusCode.OK, page("?limit=100").status)
            assertEquals(HttpStatusCode.Unauthorized, get("/v1/activities").status)
        }

    @Test
    fun theTrackIsGeoJsonOfTheAcceptedSamplesAndOnlyForTheOwner() =
        app {
            val rec = record()
            val created = upload(rec.id, rec.bytes, token = alice).summary()

            val response = get("/v1/activities/${created.id}/track") { bearerAuth(alice) }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("application/geo+json", response.contentType()?.withoutParameters()?.toString())
            val geometry =
                json
                    .parseToJsonElement(response.bodyAsText())
                    .jsonObject
                    .getValue("geometry")
                    .jsonObject
            assertEquals("LineString", geometry.getValue("type").jsonPrimitive.content)
            assertEquals(6, geometry.getValue("coordinates").jsonArray.size)

            assertEquals(HttpStatusCode.NotFound, get("/v1/activities/${created.id}/track") { bearerAuth(bob) }.status)
            assertEquals(
                HttpStatusCode.NotFound,
                get("/v1/activities/${UUID.randomUUID()}/track") { bearerAuth(alice) }.status,
            )
            assertEquals(HttpStatusCode.BadRequest, get("/v1/activities/nope/track") { bearerAuth(alice) }.status)
            assertEquals(HttpStatusCode.Unauthorized, get("/v1/activities/${created.id}/track").status)
        }

    @Test
    fun anActivityWithoutATrackYieldsANullGeometry() =
        app {
            val rec = record()
            val created = upload(rec.id, rec.bytes).summary()
            db.connection.use { it.createStatement().execute("UPDATE activities SET track = NULL") }

            val body = get("/v1/activities/${created.id}/track") { bearerAuth(alice) }.bodyAsText()
            assertTrue(json.parseToJsonElement(body).jsonObject.getValue("geometry") is JsonNull)
        }

    @Test
    fun anActivityIsOnlyVisibleToItsOwner() =
        app {
            val rec = record()
            val created = upload(rec.id, rec.bytes, token = alice).summary()

            val own = get("/v1/activities/${created.id}") { bearerAuth(alice) }
            assertEquals(HttpStatusCode.OK, own.status)
            assertEquals(created, own.summary())

            // IDOR (threat model): another user, and a nonexistent id, get the same 404, never a 403.
            assertEquals(HttpStatusCode.NotFound, get("/v1/activities/${created.id}") { bearerAuth(bob) }.status)
            assertEquals(
                HttpStatusCode.NotFound,
                get("/v1/activities/${UUID.randomUUID()}") { bearerAuth(alice) }.status,
            )

            // Another user uploading the same client id gets their own activity; Alice's is untouched.
            val bobs = upload(rec.id, rec.bytes, token = bob)
            assertEquals(HttpStatusCode.Created, bobs.status)
            assertEquals(2L, scalar("SELECT count(*) FROM activities"))
            assertEquals(
                1L,
                scalar(
                    "SELECT count(*) FROM activities a JOIN users u ON u.id = a.owner_id WHERE u.oidc_subject = 'alice'",
                ),
            )
        }

    private fun rawFileExists(key: String) =
        try {
            raw.get(key)
            true
        } catch (e: NoSuchKeyException) {
            false
        }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> =
        ZipInputStream(bytes.inputStream()).use { zip ->
            buildMap { generateSequence { zip.nextEntry }.forEach { put(it.name, zip.readBytes()) } }
        }

    private fun keysOf(subject: String): List<String> =
        db.connection.use { conn ->
            val sql =
                "SELECT raw_object_key FROM activities a JOIN users u ON u.id = a.owner_id WHERE u.oidc_subject = ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, subject)
                stmt.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            }
        }

    @Test
    fun deletingAnActivityRemovesRowAndFileAndTheSameIdIsRefusedAfterwards() =
        app {
            val rec = record()
            val created = upload(rec.id, rec.bytes).summary()
            val key = keysOf("alice").single()
            assertTrue(rawFileExists(key))

            assertEquals(HttpStatusCode.NoContent, delete("/v1/activities/${created.id}") { bearerAuth(alice) }.status)

            assertEquals(0L, scalar("SELECT count(*) FROM activities"))
            assertTrue(!rawFileExists(key), "the raw file must be gone")
            assertEquals(HttpStatusCode.NotFound, get("/v1/activities/${created.id}") { bearerAuth(alice) }.status)
            assertEquals(
                HttpStatusCode.NotFound,
                get("/v1/activities/${created.id}/track") { bearerAuth(alice) }.status,
            )
            // A device retrying the same file must not bring the activity back.
            assertEquals(HttpStatusCode.Gone, upload(rec.id, rec.bytes).status)
            assertEquals(0L, scalar("SELECT count(*) FROM activities"))
            // Deleting twice is a plain 404, and a malformed id is a 400.
            assertEquals(HttpStatusCode.NotFound, delete("/v1/activities/${created.id}") { bearerAuth(alice) }.status)
            assertEquals(HttpStatusCode.BadRequest, delete("/v1/activities/nope") { bearerAuth(alice) }.status)
            // The tombstone is per owner: another user's upload of that client id is their own activity.
            assertEquals(HttpStatusCode.Created, upload(rec.id, rec.bytes, token = bob).status)
        }

    @Test
    fun anotherUserCannotDeleteMyActivity() =
        app {
            val rec = record()
            val created = upload(rec.id, rec.bytes).summary()
            val key = keysOf("alice").single()

            assertEquals(HttpStatusCode.NotFound, delete("/v1/activities/${created.id}") { bearerAuth(bob) }.status)

            assertEquals(1L, scalar("SELECT count(*) FROM activities"))
            assertTrue(rawFileExists(key))
            assertEquals(HttpStatusCode.OK, upload(rec.id, rec.bytes).status) // still alice's, no tombstone
            assertEquals(HttpStatusCode.Unauthorized, delete("/v1/activities/${created.id}").status)
        }

    @Test
    fun deletingTheAccountRemovesItsDataAndBlocksTheAccountButNotOtherUsers() =
        app {
            val first = record()
            val second = record()
            val bobs = record()
            upload(first.id, first.bytes)
            upload(second.id, second.bytes)
            upload(bobs.id, bobs.bytes, token = bob)
            val aliceKeys = keysOf("alice")
            val bobKey = keysOf("bob").single()
            assertEquals(2, aliceKeys.size)

            assertEquals(HttpStatusCode.NoContent, delete("/v1/me") { bearerAuth(alice) }.status)

            assertTrue(aliceKeys.none(::rawFileExists))
            assertEquals(0, keysOf("alice").size)
            // The account is blocked for everything but repeating the deletion.
            assertEquals(HttpStatusCode.Forbidden, get("/v1/activities") { bearerAuth(alice) }.status)
            assertEquals(HttpStatusCode.Forbidden, get("/v1/me/export") { bearerAuth(alice) }.status)
            assertEquals(HttpStatusCode.Forbidden, upload(first.id, first.bytes).status)
            assertEquals(HttpStatusCode.NoContent, delete("/v1/me") { bearerAuth(alice) }.status)
            assertEquals(1L, scalar("SELECT count(*) FROM users WHERE deleted_at IS NOT NULL"))
            // Bob is untouched.
            assertTrue(rawFileExists(bobKey))
            val bobsList = get("/v1/activities") { bearerAuth(bob) }.bodyAsText()
            assertEquals(
                1,
                json
                    .parseToJsonElement(bobsList)
                    .jsonObject
                    .getValue("items")
                    .jsonArray.size,
            )
        }

    @Test
    fun theExportHoldsEveryRawFileUnchangedItsGpxAndTheSummaries() =
        app {
            val a = record()
            val b = record()
            val others = record()
            upload(a.id, a.bytes)
            upload(b.id, b.bytes)
            upload(others.id, others.bytes, token = bob) // someone else's data must not leak in

            val response = get("/v1/me/export") { bearerAuth(alice) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("application/zip", response.contentType()?.withoutParameters().toString())
            assertTrue(response.headers[HttpHeaders.ContentDisposition]!!.contains("averyn-export.zip"))
            val files = unzip(response.readRawBytes())
            assertEquals(5, files.size, files.keys.toString()) // 2 raw + 2 gpx + activities.json
            for (rec in listOf(a, b)) {
                assertContentEquals(rec.bytes, files.getValue("raw/${rec.id}.jsonl"))
                val gpx = String(files.getValue("gpx/${rec.id}.gpx"))
                assertTrue(gpx.contains("<gpx") && gpx.contains("<trkpt"), gpx.take(200))
            }
            val manifest = json.parseToJsonElement(String(files.getValue("activities.json"))).jsonObject
            val listed = manifest.getValue("activities").jsonArray
            assertEquals(2, listed.size)
            assertTrue(
                listed.all {
                    it.jsonObject
                        .getValue("gpxSkippedLines")
                        .jsonPrimitive.content == "0"
                },
            )
            val ids =
                listed.map {
                    it.jsonObject
                        .getValue(
                            "activity",
                        ).jsonObject
                        .getValue("clientActivityId")
                        .jsonPrimitive.content
                }
            assertEquals(setOf(a.id.toString(), b.id.toString()), ids.toSet())
            assertEquals(HttpStatusCode.Unauthorized, get("/v1/me/export").status)
        }

    @Test
    fun whenAllExportSlotsAreTakenTheServerAsksToRetryLater() {
        val slots = Semaphore(1)
        assertTrue(slots.tryAcquire())
        app(exportSlots = slots) {
            val response = get("/v1/me/export") { bearerAuth(alice) }
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("5", response.headers[HttpHeaders.RetryAfter])
        }
    }
}
