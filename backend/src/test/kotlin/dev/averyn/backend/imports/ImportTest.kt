package dev.averyn.backend.imports

import dev.averyn.backend.activities.DEFAULT_USER_QUOTA_BYTES
import dev.averyn.backend.activities.activities
import dev.averyn.backend.auth.TestIssuer
import dev.averyn.backend.auth.auth
import dev.averyn.backend.connectAndMigrate
import dev.averyn.backend.content
import dev.averyn.backend.storage.RawObjectStore
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The import path end to end (TDD-0008): a GPX file goes in, an activity with the server's own numbers comes
 * out, and the file the user uploaded is kept next to it. Real PostgreSQL/PostGIS and a real S3 API
 * (SeaweedFS, as in Compose) via Testcontainers: Docker is required.
 */
class ImportTest {
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
        val bucket = "averyn-import-test"
        val db = connectAndMigrate(postgres.jdbcUrl, postgres.username, postgres.password)
        val raw = RawObjectStore(endpoint, bucket, "test", "test")

        init {
            raw.ensureBucket()
        }
    }

    private val idp = TestIssuer()
    private val json = Json { ignoreUnknownKeys = true }

    /** One user per test: the same file is imported once per owner, so the tests must not share one. */
    private fun token(subject: String) = idp.token(subject = subject)

    /** A two-segment track: the second segment starts a minute later, so the gap is a pause. */
    private val twoSegments =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
          <trk>
            <name>Morning run</name>
            <type>running</type>
            <trkseg>
              <trkpt lat="45.0" lon="9.0"><ele>120.0</ele><time>2026-10-19T08:00:00Z</time></trkpt>
              <trkpt lat="45.001" lon="9.0"><ele>121.0</ele><time>2026-10-19T08:00:01Z</time></trkpt>
              <trkpt lat="45.002" lon="9.0"><ele>122.0</ele><time>2026-10-19T08:00:02Z</time></trkpt>
            </trkseg>
            <trkseg>
              <trkpt lat="45.010" lon="9.0"><ele>130.0</ele><time>2026-10-19T08:01:00Z</time></trkpt>
              <trkpt lat="45.011" lon="9.0"><ele>131.0</ele><time>2026-10-19T08:01:01Z</time></trkpt>
            </trkseg>
          </trk>
        </gpx>
        """.trimIndent()

    private fun app(
        quotaBytes: Long = DEFAULT_USER_QUOTA_BYTES,
        block: suspend HttpClient.() -> Unit,
    ) = testApplication {
        application {
            content()
            auth(idp.config, idp.jwks)
            activities(db, raw, quotaBytes = quotaBytes)
            imports(db, raw, quotaBytes = quotaBytes)
        }
        client.block()
    }

    private suspend fun HttpClient.importGpx(
        gpx: String,
        subject: String,
        sport: String? = "RUN",
        bodyType: ContentType = ContentType("application", "gpx+xml"),
    ): HttpResponse =
        post("/v1/imports/gpx" + (sport?.let { "?sport=$it" } ?: "")) {
            bearerAuth(token(subject))
            contentType(bodyType)
            setBody(gpx)
        }

    private fun countFor(subject: String): Long =
        db.connection.use { conn ->
            conn
                .prepareStatement(
                    "SELECT count(*) FROM activities a JOIN users u ON u.id = a.owner_id WHERE u.oidc_subject = ?",
                ).use { stmt ->
                    stmt.setString(1, subject)
                    stmt.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0 }
                }
        }

    private fun sourceKeysOf(subject: String): List<String> =
        db.connection.use { conn ->
            conn
                .prepareStatement(
                    "SELECT a.source_object_key FROM activities a JOIN users u ON u.id = a.owner_id " +
                        "WHERE u.oidc_subject = ? AND a.source_object_key IS NOT NULL",
                ).use { stmt ->
                    stmt.setString(1, subject)
                    stmt.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
                }
        }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> =
        ZipInputStream(bytes.inputStream()).use { zip ->
            buildMap { generateSequence { zip.nextEntry }.forEach { put(it.name, zip.readBytes()) } }
        }

    private fun rawFileExists(key: String) =
        try {
            raw.get(key)
            true
        } catch (e: NoSuchKeyException) {
            false
        }

    @Test
    fun aGpxFileBecomesAnActivityWithTheServersOwnNumbers() =
        app {
            val response = importGpx(twoSegments, "numbers")

            assertEquals(HttpStatusCode.Created, response.status)
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val activity = body.getValue("activity").jsonObject
            assertEquals("gpx", activity.getValue("source").jsonPrimitive.content)
            assertEquals("RUN", activity.getValue("sport").jsonPrimitive.content)
            assertEquals("COMPLETED", activity.getValue("finalState").jsonPrimitive.content)
            // A GPX track carries no accuracy: the activity is not graded, it is UNKNOWN.
            assertEquals("UNKNOWN", activity.getValue("qualityGrade").jsonPrimitive.content)
            // The gap between the two segments is a pause, not a leg.
            assertEquals(
                61_000L,
                activity
                    .getValue("elapsedMs")
                    .jsonPrimitive.content
                    .toLong(),
            )
            assertEquals(
                58_000L,
                activity
                    .getValue("pausedMs")
                    .jsonPrimitive.content
                    .toLong(),
            )
            assertEquals(
                3_000L,
                activity
                    .getValue("movingMs")
                    .jsonPrimitive.content
                    .toLong(),
            )
            assertTrue(
                activity
                    .getValue("distanceM")
                    .jsonPrimitive.content
                    .toDouble() > 200.0,
            )

            val report = body.getValue("import").jsonObject
            assertEquals("gpx", report.getValue("source").jsonPrimitive.content)
            assertEquals(
                2,
                report
                    .getValue("segments")
                    .jsonPrimitive.content
                    .toInt(),
            )
            assertEquals(
                5,
                report
                    .getValue("points")
                    .jsonPrimitive.content
                    .toInt(),
            )
        }

    @Test
    fun theTrackKeepsThePauseAsTwoLines() =
        app {
            val created = json.parseToJsonElement(importGpx(twoSegments, "track").bodyAsText()).jsonObject
            val id =
                created
                    .getValue("activity")
                    .jsonObject
                    .getValue("id")
                    .jsonPrimitive.content

            val track =
                json.parseToJsonElement(
                    get("/v1/activities/$id/track") { bearerAuth(token("track")) }.bodyAsText(),
                )
            val geometry = track.jsonObject.getValue("geometry").jsonObject
            assertEquals("MultiLineString", geometry.getValue("type").jsonPrimitive.content)
            assertEquals(2, geometry.getValue("coordinates").jsonArray.size)
        }

    @Test
    fun theSameFileIsImportedOnce() =
        app {
            val first = importGpx(twoSegments, "once")
            val second = importGpx(twoSegments, "once")

            assertEquals(HttpStatusCode.Created, first.status)
            assertEquals(HttpStatusCode.OK, second.status)
            val firstId =
                json
                    .parseToJsonElement(first.bodyAsText())
                    .jsonObject
                    .getValue("activity")
                    .jsonObject
                    .getValue("id")
                    .jsonPrimitive.content
            val secondId =
                json
                    .parseToJsonElement(second.bodyAsText())
                    .jsonObject
                    .getValue("activity")
                    .jsonObject
                    .getValue("id")
                    .jsonPrimitive.content
            assertEquals(firstId, secondId)
            assertEquals(1L, countFor("once"))
            assertEquals(1, sourceKeysOf("once").size)
        }

    @Test
    fun theOriginalFileIsKeptNextToTheActivity() =
        app {
            importGpx(twoSegments, "original")

            val key = sourceKeysOf("original").single()
            assertTrue(rawFileExists(key))
            assertTrue(String(raw.get(key)).contains("<trkseg>"), "the original file is kept byte-for-byte")

            val exported = unzip(get("/v1/me/export") { bearerAuth(token("original")) }.readRawBytes())
            val original = exported.entries.single { it.key.startsWith("original/") }
            assertContentEquals(twoSegments.toByteArray(), original.value)
        }

    @Test
    fun deletingTheActivityRemovesTheOriginalAndTheFileCanBeImportedAgain() =
        app {
            val created = json.parseToJsonElement(importGpx(twoSegments, "delete").bodyAsText()).jsonObject
            val id =
                created
                    .getValue("activity")
                    .jsonObject
                    .getValue("id")
                    .jsonPrimitive.content
            val key = sourceKeysOf("delete").single()

            assertEquals(HttpStatusCode.NoContent, delete("/v1/activities/$id") { bearerAuth(token("delete")) }.status)
            assertTrue(!rawFileExists(key), "the imported file must be gone")
            assertEquals(0L, countFor("delete"))

            // The same file is importable again: the tombstone stops a device retry, it does not blacklist the
            // file (ADR-0022).
            val again = importGpx(twoSegments, "delete")
            assertEquals(HttpStatusCode.Created, again.status)
            val againId =
                json
                    .parseToJsonElement(again.bodyAsText())
                    .jsonObject
                    .getValue("activity")
                    .jsonObject
                    .getValue("id")
                    .jsonPrimitive.content
            assertTrue(againId != id)
        }

    @Test
    fun aFileWithoutTimeIsRefused() =
        app {
            val gpx =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
                  <trk><trkseg>
                    <trkpt lat="45.0" lon="9.0"><ele>120.0</ele></trkpt>
                    <trkpt lat="45.001" lon="9.0"><ele>121.0</ele></trkpt>
                  </trkseg></trk>
                </gpx>
                """.trimIndent()

            val response = importGpx(gpx, "notime")

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("invalid_time", body.getValue("error").jsonPrimitive.content)
            assertEquals(0L, countFor("notime"))
        }

    @Test
    fun aFileThatTriesToReadTheServerIsRefused() =
        app {
            val gpx =
                """
                <?xml version="1.0"?>
                <!DOCTYPE gpx [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
                  <trk>
                    <name>&xxe;</name>
                    <trkseg>
                      <trkpt lat="45.0" lon="9.0"><time>2026-10-19T08:00:00Z</time></trkpt>
                      <trkpt lat="45.001" lon="9.0"><time>2026-10-19T08:00:01Z</time></trkpt>
                    </trkseg>
                  </trk>
                </gpx>
                """.trimIndent()

            val response = importGpx(gpx, "xxe")

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("xml_error", body.getValue("error").jsonPrimitive.content)
            assertTrue(!response.bodyAsText().contains("root:"), "the answer must not carry the file it tried to read")
            assertEquals(0L, countFor("xxe"))
        }

    @Test
    fun theSportIsExplicit() =
        app {
            assertEquals(HttpStatusCode.BadRequest, importGpx(twoSegments, "sport", sport = null).status)
            assertEquals(HttpStatusCode.BadRequest, importGpx(twoSegments, "sport", sport = "SWIM").status)
            assertEquals(HttpStatusCode.Created, importGpx(twoSegments, "sport", sport = "HIKE").status)
        }

    @Test
    fun onlyAGpxFileIsAccepted() =
        app {
            val response = importGpx(twoSegments, "type", bodyType = ContentType.Application.Json)

            assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
        }

    @Test
    fun anImportNeedsAToken() =
        app {
            val response =
                post("/v1/imports/gpx?sport=RUN") {
                    contentType(ContentType("application", "gpx+xml"))
                    setBody(twoSegments)
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun anImportedActivityBelongsToItsOwner() =
        app {
            val created = json.parseToJsonElement(importGpx(twoSegments, "owner").bodyAsText()).jsonObject
            val id =
                created
                    .getValue("activity")
                    .jsonObject
                    .getValue("id")
                    .jsonPrimitive.content

            assertEquals(HttpStatusCode.OK, get("/v1/activities/$id") { bearerAuth(token("owner")) }.status)
            assertEquals(HttpStatusCode.NotFound, get("/v1/activities/$id") { bearerAuth(token("other")) }.status)
            assertEquals(HttpStatusCode.NotFound, get("/v1/activities/$id/track") { bearerAuth(token("other")) }.status)
        }

    @Test
    fun theQuotaCountsTheImportedFileToo() =
        app(quotaBytes = 1_000) {
            val response = importGpx(twoSegments, "quota")

            assertEquals(HttpStatusCode.InsufficientStorage, response.status)
            assertEquals(0L, countFor("quota"))
        }
}
