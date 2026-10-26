package dev.averyn.backend.imports

import dev.averyn.backend.TestStack
import dev.averyn.backend.activities.activities
import dev.averyn.backend.auth.TestIssuer
import dev.averyn.backend.auth.auth
import dev.averyn.backend.content
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.zip.ZipInputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The FIT import end to end (TDD-0013): a FIT file goes in, an activity with the server's own numbers and its heart
 * rate comes out, and the file the user uploaded is kept next to it. Real PostgreSQL/PostGIS and a real S3 API via
 * Testcontainers: Docker is required.
 */
class ImportFitTest {
    private val db = TestStack.db
    private val raw = TestStack.raw
    private val idp = TestIssuer()
    private val json = Json { ignoreUnknownKeys = true }
    private val fitType = ContentType("application", "vnd.ant.fit")

    @AfterTest
    fun tearDown() {
        db.connection.use { it.createStatement().execute("TRUNCATE activities, users CASCADE") }
    }

    private fun token(subject: String) = idp.token(subject = subject)

    private fun app(block: suspend HttpClient.() -> Unit) =
        testApplication {
            application {
                content()
                auth(idp.config, idp.jwks)
                activities(db, raw)
                imports(db, raw)
            }
            client.block()
        }

    private suspend fun HttpClient.importFit(
        fit: ByteArray,
        subject: String,
        query: String = "",
        type: ContentType = fitType,
    ): HttpResponse =
        post("/v1/imports/fit$query") {
            bearerAuth(token(subject))
            contentType(type)
            setBody(fit)
        }

    private fun JsonObject.obj(name: String) = getValue(name).jsonObject

    private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content

    /** 2026-10-26T08:00:00Z. */
    private val startMs = 1_792_999_200_000L

    /** A run of [seconds] seconds with a pause in the middle (see [fitRun]). */
    private fun run(
        seconds: Int = 30,
        sport: Int? = 1,
        heartRate: Int? = 150,
    ): ByteArray = fitRun(startMs, seconds, sport, heartRate)

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
    fun aFitFileBecomesAnActivityWithItsHeartRateAndItsOwnSport() =
        app {
            val response = importFit(run(sport = 2), "fit-ok")

            assertEquals(HttpStatusCode.Created, response.status)
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val activity = body.obj("activity")
            assertEquals("fit", activity.text("source"))
            // No sport in the query: the file's own one.
            assertEquals("RIDE", activity.text("sport"))
            assertEquals("UNKNOWN", activity.text("qualityGrade"))
            assertEquals("150", activity.text("avgHrBpm"))
            assertEquals("150", activity.text("maxHrBpm"))
            assertEquals("fit", activity.text("hrSource"))
            // The minute between the two timer intervals is a pause, not distance and not moving time.
            assertTrue(activity.text("pausedMs").toLong() in 55_000L..65_000L, activity.text("pausedMs"))
            val report = body.obj("import")
            assertEquals("fit", report.text("source"))
            assertEquals("fit-import-v1", report.text("parserVersion"))
            assertEquals("2", report.text("segments"))
            assertEquals("30", report.text("points"))
            assertEquals("30", report.text("heartRateReadings"))
        }

    @Test
    fun theSportInTheQueryWinsOverTheOneInTheFile() =
        app {
            val response = importFit(run(sport = 2), "fit-sport", query = "?sport=WALK")

            assertEquals(HttpStatusCode.Created, response.status)
            assertEquals(
                "WALK",
                json
                    .parseToJsonElement(response.bodyAsText())
                    .jsonObject
                    .obj("activity")
                    .text("sport"),
            )
        }

    @Test
    fun aFileWithoutAKnownSportAsksForOne() =
        app {
            val response = importFit(run(sport = null), "fit-nosport")

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            assertEquals("sport_unknown", json.parseToJsonElement(response.bodyAsText()).jsonObject.text("error"))
            assertEquals(0L, count("fit-nosport"))
            assertEquals(
                HttpStatusCode.Created,
                importFit(run(sport = null), "fit-nosport", query = "?sport=HIKE").status,
            )
        }

    @Test
    fun aSportThatIsNotOneIsRefusedEvenForFit() =
        app {
            val response = importFit(run(), "fit-badsport", query = "?sport=SWIM")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("invalid_sport", json.parseToJsonElement(response.bodyAsText()).jsonObject.text("error"))
        }

    @Test
    fun theSameFileIsImportedOnceAndTheOriginalIsKeptByteForByte() =
        app {
            val file = run()
            val first = importFit(file, "fit-dup")
            val second = importFit(file, "fit-dup")

            assertEquals(HttpStatusCode.Created, first.status)
            assertEquals(HttpStatusCode.OK, second.status)
            val firstId =
                json
                    .parseToJsonElement(first.bodyAsText())
                    .jsonObject
                    .obj("activity")
                    .text("id")
            val secondBody = json.parseToJsonElement(second.bodyAsText()).jsonObject
            assertEquals(firstId, secondBody.obj("activity").text("id"))
            assertEquals("true", secondBody.obj("import").text("duplicate"))
            assertEquals(1L, count("fit-dup"))

            val export = get("/v1/me/export") { bearerAuth(token("fit-dup")) }
            val entries =
                ZipInputStream(export.readRawBytes().inputStream()).use { zip ->
                    buildMap { generateSequence { zip.nextEntry }.forEach { put(it.name, zip.readBytes()) } }
                }
            val original = entries.entries.single { it.key.startsWith("original/") }
            assertTrue(original.key.endsWith(".fit"), original.key)
            assertContentEquals(file, original.value)
        }

    @Test
    fun aDamagedFileIsRefusedWithItsReason() =
        app {
            val damaged = importFit(FitBuilder().build(corruptCrc = true), "fit-bad")

            assertEquals(HttpStatusCode.UnprocessableEntity, damaged.status)
            assertEquals("fit_crc", json.parseToJsonElement(damaged.bodyAsText()).jsonObject.text("error"))
            val gpx = importFit("<gpx/>".toByteArray(), "fit-bad")
            assertEquals("not_fit", json.parseToJsonElement(gpx.bodyAsText()).jsonObject.text("error"))
            assertEquals(0L, count("fit-bad"))
        }

    @Test
    fun aGpxContentTypeIsNotAFitFile() =
        app {
            val response = importFit(run(), "fit-type", type = ContentType("application", "gpx+xml"))

            assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
        }

    @Test
    fun theFitRouteNeedsASignIn() =
        app {
            val response =
                post("/v1/imports/fit") {
                    contentType(fitType)
                    setBody(run())
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
}
