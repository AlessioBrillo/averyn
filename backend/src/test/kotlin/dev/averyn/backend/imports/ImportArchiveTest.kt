package dev.averyn.backend.imports

import dev.averyn.backend.TestStack
import dev.averyn.backend.activities.activities
import dev.averyn.backend.auth.TestIssuer
import dev.averyn.backend.auth.auth
import dev.averyn.backend.content
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The import of an archive end to end (TDD-0013 §5.3): every activity file of a ZIP goes through the same service as a
 * single upload, a file that cannot be imported does not stop the others, and a decompression bomb is refused.
 * Real PostgreSQL/PostGIS and a real S3 API via Testcontainers: Docker is required.
 */
class ImportArchiveTest {
    private val db = TestStack.db
    private val raw = TestStack.raw
    private val idp = TestIssuer()
    private val json = Json { ignoreUnknownKeys = true }
    private val zipType = ContentType("application", "zip")

    /** 2026-10-26T08:00:00Z. */
    private val startMs = 1_792_999_200_000L
    private val day = 24 * 3_600_000L

    private val gpx =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
          <trk><trkseg>
            <trkpt lat="45.0" lon="9.0"><time>2026-10-19T08:00:00Z</time></trkpt>
            <trkpt lat="45.001" lon="9.0"><time>2026-10-19T08:00:01Z</time></trkpt>
          </trkseg></trk>
        </gpx>
        """.trimIndent()

    @AfterTest
    fun tearDown() {
        db.connection.use { it.createStatement().execute("TRUNCATE activities, users CASCADE") }
    }

    private fun token(subject: String) = idp.token(subject = subject)

    private fun app(
        limits: ArchiveLimits = ArchiveLimits(),
        block: suspend HttpClient.() -> Unit,
    ) = testApplication {
        application {
            content()
            auth(idp.config, idp.jwks)
            activities(db, raw)
            imports(db, raw, archiveLimits = limits)
        }
        client.block()
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private suspend fun HttpClient.importArchive(
        archive: ByteArray,
        subject: String,
        query: String = "",
        type: ContentType = zipType,
    ): HttpResponse =
        post("/v1/imports/archive$query") {
            bearerAuth(token(subject))
            contentType(type)
            setBody(archive)
        }

    private fun JsonObject.number(name: String) = getValue(name).jsonPrimitive.content.toInt()

    private fun JsonObject.entry(name: String): JsonObject =
        getValue("entries").jsonArray.map { it.jsonObject }.single { it.getValue("name").jsonPrimitive.content == name }

    private fun count(subject: String): Long =
        db.connection.use { conn ->
            conn
                .prepareStatement(
                    "SELECT count(*) FROM activities a JOIN users u ON u.id = a.owner_id WHERE u.oidc_subject = ?",
                ).use { stmt ->
                    stmt.setString(1, subject)
                    stmt.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0 }
                }
        }

    @Test
    fun everyActivityFileOfTheArchiveIsImportedAndTheReportSaysWhatHappenedToEach() =
        app {
            val archive =
                zip(
                    "activities/run-1.fit" to fitRun(startMs),
                    "activities/run-2.fit" to fitRun(startMs + day, sport = 2),
                    // The same bytes as run-1 under another name: an activity is imported once.
                    "activities/run-1b.fit" to fitRun(startMs),
                    "activities/old.gpx.gz" to gzip(gpx.toByteArray()),
                    "activities/broken.fit" to fitRun(startMs + 2 * day).also { it[20] = (it[20] + 1).toByte() },
                    "README.txt" to "my export".toByteArray(),
                    "media/photo.jpg" to ByteArray(10),
                    "__MACOSX/activities/._run-1.fit" to ByteArray(10),
                )

            val response = importArchive(archive, "arch-ok", query = "?sport=HIKE")

            assertEquals(HttpStatusCode.OK, response.status)
            val report = json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(3, report.number("created"))
            assertEquals(1, report.number("duplicates"))
            assertEquals(1, report.number("refused"))
            assertEquals(3, report.number("ignoredFiles"))
            assertEquals(
                "created",
                report
                    .entry("activities/run-1.fit")
                    .getValue("status")
                    .jsonPrimitive.content,
            )
            assertEquals(
                "duplicate",
                report
                    .entry("activities/run-1b.fit")
                    .getValue("status")
                    .jsonPrimitive.content,
            )
            val broken = report.entry("activities/broken.fit")
            assertEquals("refused", broken.getValue("status").jsonPrimitive.content)
            assertEquals("fit_crc", broken.getValue("code").jsonPrimitive.content)
            // The gzipped GPX was read: its sport is the one in the query.
            assertEquals(
                "created",
                report
                    .entry("activities/old.gpx.gz")
                    .getValue("status")
                    .jsonPrimitive.content,
            )
            assertEquals(3L, count("arch-ok"))
        }

    @Test
    fun aGpxFileWithoutASportAsksForOneOnlyForItself() =
        app {
            val archive = zip("a.fit" to fitRun(startMs), "b.gpx" to gpx.toByteArray())

            val report = json.parseToJsonElement(importArchive(archive, "arch-sport").bodyAsText()).jsonObject

            assertEquals(1, report.number("created"))
            assertEquals(1, report.number("refused"))
            assertEquals(
                "sport_unknown",
                report
                    .entry("b.gpx")
                    .getValue("code")
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun importingTheSameArchiveAgainStoresNothingNew() =
        app {
            val archive = zip("a.fit" to fitRun(startMs), "b.fit" to fitRun(startMs + day))
            importArchive(archive, "arch-again")

            val report = json.parseToJsonElement(importArchive(archive, "arch-again").bodyAsText()).jsonObject

            assertEquals(0, report.number("created"))
            assertEquals(2, report.number("duplicates"))
            assertEquals(2L, count("arch-again"))
        }

    @Test
    fun anArchiveThatExpandsTooMuchIsRefusedAndNothingIsStored() =
        app(limits = ArchiveLimits(maxFileBytes = 1024 * 1024, maxTotalBytes = 3 * 1024 * 1024)) {
            // Each entry is small once compressed and 2 MB once unpacked: four of them pass the total.
            val zeros = ByteArray(2 * 1024 * 1024)
            val archive = zip("a.fit" to zeros, "b.fit" to zeros, "c.fit" to zeros, "d.fit" to zeros)

            val response = importArchive(archive, "arch-bomb")

            // Every entry is over the limit of one file, so each one is refused on its own...
            assertEquals(HttpStatusCode.OK, response.status)
            val report = json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(4, report.number("refused"))
            assertEquals(
                "entry_too_large",
                report
                    .entry("a.fit")
                    .getValue("code")
                    .jsonPrimitive.content,
            )
            assertEquals(0L, count("arch-bomb"))
        }

    @Test
    fun theWholeArchiveIsRefusedWhenTheUnpackedTotalPassesTheLimit() =
        app(limits = ArchiveLimits(maxFileBytes = 2 * 1024 * 1024, maxTotalBytes = 3 * 1024 * 1024)) {
            val zeros = ByteArray(2 * 1024 * 1024)
            val archive = zip("a.fit" to zeros, "b.fit" to zeros)

            val response = importArchive(archive, "arch-total")

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            assertEquals(
                "archive_too_large",
                json
                    .parseToJsonElement(response.bodyAsText())
                    .jsonObject
                    .getValue("error")
                    .jsonPrimitive.content,
            )
            assertEquals(0L, count("arch-total"))
        }

    @Test
    fun anArchiveWithTooManyActivityFilesIsRefusedBeforeReadingAny() =
        app(limits = ArchiveLimits(maxFiles = 2)) {
            val archive =
                zip(
                    "a.fit" to fitRun(startMs),
                    "b.fit" to fitRun(startMs + day),
                    "c.fit" to fitRun(startMs + 2 * day),
                )

            val response = importArchive(archive, "arch-many")

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            assertEquals(
                "archive_too_large",
                json
                    .parseToJsonElement(response.bodyAsText())
                    .jsonObject
                    .getValue("error")
                    .jsonPrimitive.content,
            )
            assertEquals(0L, count("arch-many"))
        }

    @Test
    fun aFileThatIsNotAZipIsRefused() =
        app {
            val response = importArchive("not a zip at all".toByteArray(), "arch-bad")

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            assertEquals(
                "zip_error",
                json
                    .parseToJsonElement(response.bodyAsText())
                    .jsonObject
                    .getValue("error")
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun theTypeTheSportAndTheSignInAreChecked() =
        app {
            assertEquals(
                HttpStatusCode.UnsupportedMediaType,
                importArchive(zip("a.fit" to fitRun(startMs)), "arch-type", type = ContentType.Text.Plain).status,
            )
            assertEquals(
                HttpStatusCode.BadRequest,
                importArchive(zip("a.fit" to fitRun(startMs)), "arch-type", query = "?sport=SWIM").status,
            )
            val anonymous =
                post("/v1/imports/archive") {
                    contentType(zipType)
                    setBody(zip("a.fit" to fitRun(startMs)))
                }
            assertEquals(HttpStatusCode.Unauthorized, anonymous.status)
        }
}
