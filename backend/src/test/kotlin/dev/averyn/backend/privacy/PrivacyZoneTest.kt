package dev.averyn.backend.privacy

import com.auth0.jwt.JWT
import dev.averyn.api.PrivacyZone
import dev.averyn.api.PrivacyZones
import dev.averyn.api.Track
import dev.averyn.api.TrackGeometry
import dev.averyn.backend.TestStack
import dev.averyn.backend.activities.UserData
import dev.averyn.backend.auth.TestIssuer
import dev.averyn.backend.auth.auth
import dev.averyn.backend.auth.userIdFor
import dev.averyn.backend.content
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The privacy zones API against a real PostgreSQL (Testcontainers): Docker is required. */
class PrivacyZoneTest {
    private val db = TestStack.db
    private val raw = TestStack.raw
    private val idp = TestIssuer()
    private val alice get() = idp.token(subject = "alice")
    private val bob get() = idp.token(subject = "bob")
    private val json = Json { ignoreUnknownKeys = true }
    private val home = PrivacyZone(45.464, 9.19, 200, "Home")
    private val work = PrivacyZone(45.5, 9.2, 100)

    @AfterTest
    fun tearDown() {
        db.connection.use { it.createStatement().execute("TRUNCATE activities, users CASCADE") }
    }

    private fun app(block: suspend HttpClient.() -> Unit) =
        testApplication {
            application {
                content()
                auth(idp.config, idp.jwks)
                privacyZones(db)
            }
            client.block()
        }

    private suspend fun HttpClient.putZones(
        body: String,
        token: String? = alice,
    ): HttpResponse =
        put("/v1/privacy-zones") {
            token?.let { bearerAuth(it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpClient.save(
        zones: PrivacyZones,
        token: String = alice,
    ) = putZones(json.encodeToString(PrivacyZones.serializer(), zones), token)

    private suspend fun HttpClient.zonesOf(token: String): PrivacyZones =
        json.decodeFromString(PrivacyZones.serializer(), get("/v1/privacy-zones") { bearerAuth(token) }.bodyAsText())

    private fun count(sql: String): Long =
        db.connection.use { conn ->
            conn.createStatement().executeQuery(sql).use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }

    private fun principalOf(token: String) = JWTPrincipal(JWT.decode(token))

    @Test
    fun anAccountWithoutZonesGetsNone() =
        app {
            assertEquals(PrivacyZones(), zonesOf(alice))
        }

    @Test
    fun savedZonesComeBackInOrderAndAreReplacedAsAWhole() =
        app {
            assertEquals(HttpStatusCode.OK, save(PrivacyZones(listOf(home, work))).status)
            assertEquals(PrivacyZones(listOf(home, work)), zonesOf(alice))

            save(PrivacyZones(listOf(work)))
            assertEquals(PrivacyZones(listOf(work)), zonesOf(alice))

            save(PrivacyZones())
            assertEquals(PrivacyZones(), zonesOf(alice))
            assertEquals(0L, count("SELECT count(*) FROM privacy_zones"))
        }

    @Test
    fun zonesArePrivateToTheirOwner() =
        app {
            save(PrivacyZones(listOf(home)))
            assertEquals(PrivacyZones(), zonesOf(bob))
            save(PrivacyZones(listOf(work)), token = bob)
            assertEquals(PrivacyZones(listOf(home)), zonesOf(alice))
            assertEquals(PrivacyZones(listOf(work)), zonesOf(bob))
        }

    @Test
    fun zonesOutsideTheRulesAreRefusedAndNothingIsStored() =
        app {
            save(PrivacyZones(listOf(home)))
            val bad =
                listOf(
                    json.encodeToString(PrivacyZones.serializer(), PrivacyZones(List(11) { home })),
                    json.encodeToString(PrivacyZones.serializer(), PrivacyZones(listOf(home.copy(radiusM = 10)))),
                    json.encodeToString(PrivacyZones.serializer(), PrivacyZones(listOf(home.copy(latitude = 95.0)))),
                    """{"zones":[{"latitude":"north","longitude":9,"radiusM":100}]}""",
                    "not json",
                    "",
                    """{"zones":[],"padding":"${"x".repeat(5000)}"}""",
                )
            for (body in bad) {
                assertEquals(HttpStatusCode.UnprocessableEntity, putZones(body).status, body.take(60))
            }
            assertEquals(PrivacyZones(listOf(home)), zonesOf(alice))
        }

    @Test
    fun theZonesNeedASignIn() =
        app {
            assertEquals(HttpStatusCode.Unauthorized, get("/v1/privacy-zones").status)
            assertEquals(HttpStatusCode.Unauthorized, putZones("""{"zones":[]}""", token = null).status)
        }

    @Test
    fun anErrorNeverRepeatsACoordinate() =
        app {
            val response = save(PrivacyZones(listOf(home.copy(radiusM = 5))))
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            assertTrue("45.464" !in response.bodyAsText())
        }

    @Test
    fun aDeletedAccountLosesItsZonesAndIsRefused() =
        app {
            save(PrivacyZones(listOf(home, work)))
            val owner = db.userIdFor(principalOf(alice))
            assertEquals(2L, count("SELECT count(*) FROM privacy_zones"))

            UserData(db, raw).deleteAccount(owner)

            assertEquals(0L, count("SELECT count(*) FROM privacy_zones"))
            assertEquals(HttpStatusCode.Forbidden, get("/v1/privacy-zones") { bearerAuth(alice) }.status)
        }

    @Test
    fun theExportHoldsTheZones() =
        app {
            save(PrivacyZones(listOf(home)))
            val owner = db.userIdFor(principalOf(alice))
            val bytes = ByteArrayOutputStream().also { UserData(db, raw).export(owner, it) }.toByteArray()

            val entries = mutableMapOf<String, String>()
            ZipInputStream(bytes.inputStream()).use { zip ->
                generateSequence { zip.nextEntry }.forEach { entries[it.name] = zip.readBytes().decodeToString() }
            }

            val exported = assertNotNull(entries["privacy-zones.json"])
            assertEquals(PrivacyZones(listOf(home)), json.decodeFromString(PrivacyZones.serializer(), exported))
        }

    @Test
    fun theCutOfAFeatureLeavesAnUntouchedStringWhenThereAreNoZones() {
        val feature = """{"type":"Feature","properties":{},"geometry":{"type":"MultiLineString","coordinates":[]}}"""

        assertSame(feature, hideZonesIn(feature, PrivacyZones()))
        val cut =
            hideZonesIn(
                json.encodeToString(
                    Track.serializer(),
                    Track(
                        geometry = TrackGeometry(coordinates = listOf(List(4) { listOf(9.19, 45.464 + it * 0.001) })),
                    ),
                ),
                PrivacyZones(listOf(PrivacyZone(45.464, 9.19, 150))),
            )
        val kept = listOf(listOf(9.19, 45.464 + 2 * 0.001), listOf(9.19, 45.464 + 3 * 0.001))
        assertEquals(listOf(kept), json.decodeFromString(Track.serializer(), cut).geometry?.coordinates)
    }
}
