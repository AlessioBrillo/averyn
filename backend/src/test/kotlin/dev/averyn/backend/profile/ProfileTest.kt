package dev.averyn.backend.profile

import com.auth0.jwt.JWT
import dev.averyn.api.UnitSystem
import dev.averyn.api.UserProfile
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

/** The profile API against a real PostgreSQL (Testcontainers): Docker is required. */
class ProfileTest {
    private val db = TestStack.db
    private val raw = TestStack.raw
    private val idp = TestIssuer()
    private val alice get() = idp.token(subject = "alice")
    private val bob get() = idp.token(subject = "bob")
    private val json = Json { ignoreUnknownKeys = true }

    @AfterTest
    fun tearDown() {
        db.connection.use { it.createStatement().execute("TRUNCATE activities, users CASCADE") }
    }

    private fun app(block: suspend HttpClient.() -> Unit) =
        testApplication {
            application {
                content()
                auth(idp.config, idp.jwks)
                profile(db)
            }
            client.block()
        }

    private suspend fun HttpClient.putProfile(
        body: String,
        token: String? = alice,
    ): HttpResponse =
        put("/v1/profile") {
            token?.let { bearerAuth(it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpClient.profileOf(token: String): UserProfile =
        json.decodeFromString(UserProfile.serializer(), get("/v1/profile") { bearerAuth(token) }.bodyAsText())

    private fun count(sql: String): Long =
        db.connection.use { conn ->
            conn.createStatement().executeQuery(sql).use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }

    /** The principal the server would build from a token, for the calls that need an owner id. */
    private fun principalOf(token: String) = JWTPrincipal(JWT.decode(token))

    @Test
    fun anAccountWithoutAProfileGetsTheDefaults() =
        app {
            assertEquals(UserProfile(UnitSystem.METRIC, null), profileOf(alice))
        }

    @Test
    fun aSavedProfileComesBackAndCanBeReplaced() =
        app {
            val saved = putProfile("""{"units":"imperial","maxHrBpm":187}""")
            assertEquals(HttpStatusCode.OK, saved.status)
            assertEquals(UserProfile(UnitSystem.IMPERIAL, 187), profileOf(alice))

            // A replace: the maximum heart rate is gone.
            putProfile("""{"units":"metric"}""")
            assertEquals(UserProfile(UnitSystem.METRIC, null), profileOf(alice))
        }

    @Test
    fun theBoundsOfTheMaximumHeartRateAreInclusive() =
        app {
            assertEquals(HttpStatusCode.OK, putProfile("""{"units":"metric","maxHrBpm":100}""").status)
            assertEquals(HttpStatusCode.OK, putProfile("""{"units":"metric","maxHrBpm":230}""").status)
        }

    @Test
    fun anInvalidProfileIsRefusedAndNothingIsStored() =
        app {
            putProfile("""{"units":"imperial","maxHrBpm":190}""")
            val padding = "x".repeat(2000)
            val bad =
                listOf(
                    """{"units":"metric","maxHrBpm":99}""",
                    """{"units":"metric","maxHrBpm":231}""",
                    """{"units":"furlongs"}""",
                    """{"units":"metric","maxHrBpm":"fast"}""",
                    "not json",
                    "",
                    """{"units":"metric","padding":"$padding"}""",
                )
            for (body in bad) {
                assertEquals(HttpStatusCode.UnprocessableEntity, putProfile(body).status, body.take(60))
            }
            assertEquals(UserProfile(UnitSystem.IMPERIAL, 190), profileOf(alice))
        }

    @Test
    fun aProfileIsPrivateToItsOwner() =
        app {
            putProfile("""{"units":"imperial","maxHrBpm":187}""")
            assertEquals(UserProfile(), profileOf(bob))
            assertEquals(UserProfile(UnitSystem.IMPERIAL, 187), profileOf(alice))
        }

    @Test
    fun theProfileNeedsASignIn() =
        app {
            assertEquals(HttpStatusCode.Unauthorized, get("/v1/profile").status)
            assertEquals(HttpStatusCode.Unauthorized, putProfile("""{"units":"metric"}""", token = null).status)
        }

    @Test
    fun aDeletedAccountGetsForbiddenAndItsProfileIsGone() =
        app {
            putProfile("""{"units":"imperial","maxHrBpm":187}""")
            val owner = db.userIdFor(principalOf(alice))
            assertEquals(1L, count("SELECT count(*) FROM user_profiles"))

            UserData(db, raw).deleteAccount(owner)

            assertEquals(0L, count("SELECT count(*) FROM user_profiles"))
            assertEquals(HttpStatusCode.Forbidden, get("/v1/profile") { bearerAuth(alice) }.status)
            assertEquals(HttpStatusCode.Forbidden, putProfile("""{"units":"metric"}""").status)
        }

    @Test
    fun theExportHoldsTheProfile() =
        app {
            putProfile("""{"units":"imperial","maxHrBpm":187}""")
            val owner = db.userIdFor(principalOf(alice))

            val out = ByteArrayOutputStream()
            UserData(db, raw).export(owner, out)

            val entries = mutableMapOf<String, String>()
            ZipInputStream(out.toByteArray().inputStream()).use { zip ->
                generateSequence { zip.nextEntry }.forEach { entries[it.name] = zip.readBytes().decodeToString() }
            }
            val exported = assertNotNull(entries["profile.json"], "profile.json is part of the export: ${entries.keys}")
            assertEquals(
                UserProfile(UnitSystem.IMPERIAL, 187),
                json.decodeFromString(UserProfile.serializer(), exported),
            )
        }
}
