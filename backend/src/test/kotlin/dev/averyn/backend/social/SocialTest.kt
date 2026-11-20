package dev.averyn.backend.social

import com.auth0.jwt.JWT
import dev.averyn.api.FollowRequests
import dev.averyn.api.PublicActivity
import dev.averyn.api.PublicActivityPage
import dev.averyn.api.RelationResult
import dev.averyn.api.SocialProfile
import dev.averyn.api.UserCard
import dev.averyn.api.UserRef
import dev.averyn.api.Visibilities
import dev.averyn.api.VisibilityResult
import dev.averyn.backend.TestStack
import dev.averyn.backend.activities.activities
import dev.averyn.backend.auth.TestIssuer
import dev.averyn.backend.auth.auth
import dev.averyn.backend.auth.userIdFor
import dev.averyn.backend.content
import dev.averyn.domain.LocationSample
import dev.averyn.domain.Sport
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
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.testing.testApplication
import kotlinx.io.files.Path
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The social foundation against a real PostgreSQL (Testcontainers): Docker is required. */
class SocialTest {
    private val db = TestStack.db
    private val raw = TestStack.raw
    private val idp = TestIssuer()
    private val json = Json { ignoreUnknownKeys = true }

    private val alice get() = idp.token(subject = "alice")
    private val bob get() = idp.token(subject = "bob")
    private val carol get() = idp.token(subject = "carol")
    private val dave get() = idp.token(subject = "dave")

    @AfterTest
    fun tearDown() {
        db.connection.use { it.createStatement().execute("TRUNCATE activities, reports, users CASCADE") }
    }

    private fun app(block: suspend HttpClient.() -> Unit) =
        testApplication {
            application {
                content()
                auth(idp.config, idp.jwks)
                social(db, raw)
                activities(db, raw)
            }
            client.block()
        }

    private fun idOf(token: String): UUID = db.userIdFor(JWTPrincipal(JWT.decode(token)))

    private fun insertActivity(
        owner: UUID,
        visibility: String,
        wkt: String = "LINESTRING(9.19 45.464, 9.2 45.47, 9.21 45.48)",
    ): UUID {
        val id = UUID.randomUUID()
        db.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    INSERT INTO activities (id, owner_id, client_activity_id, sport, started_at, final_state,
                        raw_object_key, raw_sha256, raw_size_bytes, distance_m, elapsed_ms, moving_ms, paused_ms,
                        quality_grade, metrics_algorithm_version, quality_algorithm_version, track, visibility,
                        average_speed_mps, avg_hr_bpm, max_hr_bpm, hr_source)
                    VALUES (?, ?, ?, 'RUN', now(), 'COMPLETED', 'raw/x', repeat('a', 64), 1, 5000, 1800000, 1800000, 0,
                        'GOOD', 'm', 'q', ST_GeomFromText(?, 4326), ?, 2.7, 151, 187, 'ble-hr')
                    """.trimIndent(),
                ).use { stmt ->
                    stmt.setObject(1, id)
                    stmt.setObject(2, owner)
                    stmt.setObject(3, UUID.randomUUID())
                    stmt.setString(4, wkt)
                    stmt.setString(5, visibility)
                    stmt.executeUpdate()
                }
        }
        return id
    }

    private fun scalar(sql: String): Any? =
        db.connection.use { conn ->
            conn.createStatement().executeQuery(sql).use { rs -> if (rs.next()) rs.getObject(1) else null }
        }

    private suspend fun HttpClient.setProfile(
        token: String,
        handle: String? = null,
        extra: SocialProfile.() -> SocialProfile = { this },
    ): HttpResponse =
        put("/v1/me/public-profile") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    SocialProfile.serializer(),
                    SocialProfile(handle = handle, displayName = handle, ageConfirmed = true).extra(),
                ),
            )
        }

    private suspend fun HttpClient.send(
        method: String,
        path: String,
        token: String?,
        body: String? = null,
    ): HttpResponse {
        val build: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            token?.let { bearerAuth(it) }
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        return when (method) {
            "GET" -> get(path, build)
            "POST" -> post(path, build)
            "PUT" -> put(path, build)
            else -> delete(path, build)
        }
    }

    private suspend fun HttpClient.page(
        viewer: String,
        owner: UUID,
    ): PublicActivityPage? =
        get("/v1/users/$owner/activities") { bearerAuth(viewer) }.let {
            if (it.status ==
                HttpStatusCode.OK
            ) {
                json.decodeFromString(PublicActivityPage.serializer(), it.bodyAsText())
            } else {
                null
            }
        }

    /** [follower] follows [followed] and is accepted. */
    private suspend fun HttpClient.befriend(
        follower: String,
        followed: String,
    ) {
        assertEquals(HttpStatusCode.OK, send("POST", "/v1/users/${idOf(followed)}/follow", follower).status)
        assertEquals(
            HttpStatusCode.NoContent,
            send("POST", "/v1/me/follow-requests/${idOf(follower)}/accept", followed).status,
        )
    }

    // ---- the rule itself ----

    @Test
    fun theVisibilityRuleIsOneTable() {
        fun r(
            self: Boolean = false,
            follow: String? = null,
            byMe: Boolean = false,
            byThem: Boolean = false,
            priv: Boolean = false,
        ) = Relation(self, follow, byMe, byThem, priv).visibleVisibilities()
        assertEquals(Visibilities.ALL, r(self = true))
        assertEquals(setOf(Visibilities.PUBLIC), r())
        assertEquals(setOf(Visibilities.PUBLIC), r(follow = "pending"))
        assertEquals(emptySet(), r(priv = true))
        assertEquals(emptySet(), r(priv = true, follow = "pending"))
        assertEquals(setOf(Visibilities.FOLLOWERS, Visibilities.PUBLIC), r(follow = "active"))
        assertEquals(setOf(Visibilities.FOLLOWERS, Visibilities.PUBLIC), r(follow = "active", priv = true))
        assertEquals(emptySet(), r(follow = "active", byMe = true))
        assertEquals(emptySet(), r(follow = "active", byThem = true))
        assertEquals(emptySet(), r(byThem = true))
    }

    @Test
    fun noRouteReadsSomeoneElsesActivityWithoutTheAccessDoor() {
        // The social package may read the activities table in one file only (TDD-0018 F3).
        val dir =
            java.nio.file.Path
                .of("src/main/kotlin/dev/averyn/backend/social")
        val offenders =
            Files.list(dir).use { files ->
                files
                    .filter { it.fileName.toString().endsWith(".kt") && it.fileName.toString() != "ActivityAccess.kt" }
                    .filter {
                        Regex(
                            """(FROM|JOIN)\s+activities""",
                            RegexOption.IGNORE_CASE,
                        ).containsMatchIn(Files.readString(it))
                    }.map { it.fileName.toString() }
                    .toList()
            }
        assertEquals(emptyList(), offenders)
    }

    // ---- who sees what (the IDOR matrix) ----

    @Test
    fun everyViewerSeesExactlyWhatTheRuleSaysAndNothingMore() =
        app {
            val owner = alice
            val aliceId = idOf(owner)
            val ids = Visibilities.ALL.associateWith { insertActivity(aliceId, it) }
            befriend(bob, owner) // bob: follower
            send("POST", "/v1/users/$aliceId/follow", carol) // carol: pending
            val blockedViewer = dave
            idOf(blockedViewer)
            send("PUT", "/v1/me/blocks/${idOf(blockedViewer)}", owner) // blocked by alice

            // What each viewer may see. `link` opens only through its token, except for the owner.
            val allowedFor =
                mapOf(
                    owner to Visibilities.ALL,
                    bob to setOf(Visibilities.FOLLOWERS, Visibilities.PUBLIC),
                    carol to setOf(Visibilities.PUBLIC),
                    blockedViewer to emptySet(),
                )
            for ((viewer, allowed) in allowedFor) {
                val seen = page(viewer, aliceId)
                if (viewer == blockedViewer) {
                    assertNull(seen, "a blocked viewer gets a 404")
                } else {
                    assertEquals(
                        allowed
                            .map {
                                ids.getValue(it).toString()
                            }.toSet(),
                        checkNotNull(seen).items.map { it.id }.toSet(),
                        "list for $viewer",
                    )
                }
                for ((visibility, id) in ids) {
                    val status = if (visibility in allowed) HttpStatusCode.OK else HttpStatusCode.NotFound
                    assertEquals(
                        status,
                        get("/v1/users/$aliceId/activities/$id") {
                            bearerAuth(viewer)
                        }.status,
                        "$viewer reads $visibility",
                    )
                    assertEquals(
                        status,
                        get("/v1/users/$aliceId/activities/$id/track") {
                            bearerAuth(viewer)
                        }.status,
                        "$viewer reads the track of $visibility",
                    )
                }
            }
            // A stranger and a signed-out caller get nothing from the authenticated door.
            assertEquals(HttpStatusCode.Unauthorized, get("/v1/users/$aliceId/activities").status)
            assertEquals(
                HttpStatusCode.NotFound,
                get("/v1/users/$aliceId/activities/${UUID.randomUUID()}") { bearerAuth(bob) }.status,
                "a missing id looks like a hidden one",
            )
        }

    @Test
    fun aPrivateProfileShowsNothingToPeopleWhoDoNotFollow() =
        app {
            val aliceId = idOf(alice)
            val pub = insertActivity(aliceId, Visibilities.PUBLIC)
            val fol = insertActivity(aliceId, Visibilities.FOLLOWERS)
            setProfile(alice, "alice") { copy(profilePrivate = true, bio = "secret") }
            idOf(bob)

            assertEquals(emptyList(), checkNotNull(page(bob, aliceId)).items)
            val card =
                json.decodeFromString(
                    UserCard.serializer(),
                    get("/v1/users/$aliceId") { bearerAuth(bob) }.bodyAsText(),
                )
            assertTrue(card.restricted)
            assertNull(card.bio)
            assertEquals("alice", card.handle)
            assertEquals(
                HttpStatusCode.NotFound,
                get("/v1/users/$aliceId/activities/$pub") { bearerAuth(bob) }.status,
            )

            befriend(bob, alice)
            assertEquals(
                setOf(pub.toString(), fol.toString()),
                checkNotNull(page(bob, aliceId)).items.map { it.id }.toSet(),
            )
            val full =
                json.decodeFromString(
                    UserCard.serializer(),
                    get("/v1/users/$aliceId") { bearerAuth(bob) }.bodyAsText(),
                )
            assertEquals("secret", full.bio)
        }

    @Test
    fun aNonOwnerViewNeverCarriesAHealthValue() =
        app {
            val aliceId = idOf(alice)
            val id = insertActivity(aliceId, Visibilities.PUBLIC)
            idOf(bob)
            val body = get("/v1/users/$aliceId/activities/$id") { bearerAuth(bob) }.bodyAsText()
            val list = get("/v1/users/$aliceId/activities") { bearerAuth(bob) }.bodyAsText()
            for (text in listOf(body, list)) {
                assertFalse(Regex("(?i)hr|heart|rr|stress").containsMatchIn(text), text)
            }
            assertEquals(json.decodeFromString(PublicActivity.serializer(), body).sport, "RUN")
        }

    @Test
    fun theTrackOfAnotherPersonHasTheirPrivacyZoneCutOut() =
        app {
            val aliceId = idOf(alice)
            val id = insertActivity(aliceId, Visibilities.PUBLIC)
            idOf(bob)
            val before = get("/v1/users/$aliceId/activities/$id/track") { bearerAuth(bob) }.bodyAsText()
            assertTrue(before.contains("[9.19,45.464]"), "without a zone the start is there: $before")

            // The zones module is not installed in this app: write the zone straight into the table the store reads.
            db.connection.use { conn ->
                conn.createStatement().execute(
                    "INSERT INTO privacy_zones (owner_id, position, latitude, longitude, radius_m) " +
                        "VALUES ('$aliceId', 0, 45.464, 9.19, 300) ON CONFLICT DO NOTHING",
                )
            }
            val after = get("/v1/users/$aliceId/activities/$id/track") { bearerAuth(bob) }.bodyAsText()
            assertFalse(after.contains("[9.19,45.464]"), "the start is cut: $after")
        }

    // ---- the link ----

    @Test
    fun aLinkWorksSignedOutIsNeverIndexedAndStopsWhenTheVisibilityChanges() =
        app {
            val aliceId = idOf(alice)
            val id = insertActivity(aliceId, Visibilities.PRIVATE)
            assertEquals(HttpStatusCode.NotFound, get("/v1/shared/anything-that-is-not-a-token-1234").status)

            val set = send("PUT", "/v1/activities/$id/visibility", alice, """{"visibility":"link"}""")
            assertEquals(HttpStatusCode.OK, set.status)
            val token = checkNotNull(json.decodeFromString(VisibilityResult.serializer(), set.bodyAsText()).shareToken)
            assertTrue(token.length >= 20)
            // Setting link again keeps the same token: an old link is not broken by a second save.
            val again = send("PUT", "/v1/activities/$id/visibility", alice, """{"visibility":"link"}""")
            assertEquals(token, json.decodeFromString(VisibilityResult.serializer(), again.bodyAsText()).shareToken)

            val page = get("/v1/shared/$token")
            assertEquals(HttpStatusCode.OK, page.status)
            assertEquals("noindex, nofollow", page.headers["X-Robots-Tag"])
            assertEquals(HttpStatusCode.OK, get("/v1/shared/$token/track").status)
            assertEquals("noindex, nofollow", get("/v1/shared/$token/track").headers["X-Robots-Tag"])
            // A signed-in person who is not the owner gets the same answer: a link is not listed for anyone.
            assertEquals(
                HttpStatusCode.NotFound,
                get("/v1/users/$aliceId/activities/$id") { bearerAuth(bob) }.status,
            )

            send("PUT", "/v1/activities/$id/visibility", alice, """{"visibility":"private"}""")
            assertEquals(HttpStatusCode.NotFound, get("/v1/shared/$token").status)
            assertEquals(HttpStatusCode.NotFound, get("/v1/shared/$token/track").status)
            assertNull(scalar("SELECT share_token FROM activities"))

            assertEquals(
                HttpStatusCode.UnprocessableEntity,
                send("PUT", "/v1/activities/$id/visibility", alice, """{"visibility":"everyone"}""").status,
            )
            assertEquals(
                HttpStatusCode.NotFound,
                send("PUT", "/v1/activities/$id/visibility", bob, """{"visibility":"public"}""").status,
                "only the owner changes it",
            )
        }

    @Test
    fun everyResponseOnTheirPathsCarriesNoIndex() =
        app {
            val aliceId = idOf(alice)
            idOf(bob)
            assertEquals("noindex, nofollow", get("/v1/users/$aliceId") { bearerAuth(bob) }.headers["X-Robots-Tag"])
            assertEquals(
                "noindex, nofollow",
                get("/v1/users/${UUID.randomUUID()}") { bearerAuth(bob) }.headers["X-Robots-Tag"],
            )
        }

    // ---- profile and handle ----

    @Test
    fun aHandleFollowsItsRules() =
        app {
            assertEquals(HttpStatusCode.UnprocessableEntity, setProfile(alice, "ab").status, "too short")
            assertEquals(HttpStatusCode.UnprocessableEntity, setProfile(alice, "has space").status)
            assertEquals(HttpStatusCode.UnprocessableEntity, setProfile(alice, "admin").status, "reserved")
            assertEquals(
                HttpStatusCode.UnprocessableEntity,
                setProfile(alice, "alice") { copy(ageConfirmed = false) }.status,
                "a handle needs the age confirmation",
            )
            assertEquals(HttpStatusCode.OK, setProfile(alice, "  Alice.Runs ").status)
            val mine =
                json.decodeFromString(
                    SocialProfile.serializer(),
                    get("/v1/me/public-profile") {
                        bearerAuth(alice)
                    }.bodyAsText(),
                )
            assertEquals("alice.runs", mine.handle)
            assertTrue(mine.ageConfirmed)
            assertEquals(HttpStatusCode.Conflict, setProfile(bob, "ALICE.runs").status, "unique ignoring case")

            // The first change is free, the second one waits 30 days.
            assertEquals(HttpStatusCode.OK, setProfile(alice, "alice.two").status)
            assertEquals(HttpStatusCode.Conflict, setProfile(alice, "alice.three").status)
            // Saving without a handle keeps the one it has.
            assertEquals(HttpStatusCode.OK, setProfile(alice, null).status)
            assertEquals(
                "alice.two",
                json
                    .decodeFromString(
                        SocialProfile.serializer(),
                        get("/v1/me/public-profile") {
                            bearerAuth(alice)
                        }.bodyAsText(),
                    ).handle,
            )
            val byHandle = get("/v1/users/by-handle/ALICE.two") { bearerAuth(bob) }
            assertEquals(HttpStatusCode.OK, byHandle.status)
            assertEquals(HttpStatusCode.NotFound, get("/v1/users/by-handle/nobody") { bearerAuth(bob) }.status)
        }

    @Test
    fun theDefaultVisibilityIsPrivateAndOnlyAppliesToNewActivities() =
        app {
            val dir = Files.createTempDirectory("averyn-social-test")
            try {
                fun upload(): UUID {
                    val id = UUID.randomUUID()
                    val recorder = ActivityRecorder(ActivityStore(Path(dir.toString())))
                    recorder.start(id.toString(), Sport.RUN, 0, 1_700_000_000_000)
                    recorder.gpsReady(0, 1_700_000_000_000)
                    val step = 100.0 / 111_195.08
                    for (i in 0..5) {
                        recorder.onSample(
                            LocationSample(1_700_000_000_000 + i * 10_000L, i * 10_000L, i * step, 8.0, 5.0),
                        )
                    }
                    recorder.stop(60_000, 1_700_000_060_000)
                    return id.also { uploadedBytes[it] = File(dir.toFile(), "$it.jsonl").readBytes() }
                }

                suspend fun uploadTo(id: UUID) =
                    put("/v1/activities/$id") {
                        bearerAuth(alice)
                        setBody(uploadedBytes.getValue(id))
                    }
                val first = upload()
                assertEquals(HttpStatusCode.Created, uploadTo(first).status)
                assertEquals("private", scalar("SELECT visibility FROM activities WHERE client_activity_id = '$first'"))

                assertEquals(
                    HttpStatusCode.OK,
                    setProfile(alice, "alice") { copy(defaultVisibility = "followers") }.status,
                )
                val second = upload()
                assertEquals(HttpStatusCode.Created, uploadTo(second).status)
                assertEquals(
                    "followers",
                    scalar("SELECT visibility FROM activities WHERE client_activity_id = '$second'"),
                )
                assertEquals("private", scalar("SELECT visibility FROM activities WHERE client_activity_id = '$first'"))
                // A retry of the first upload changes nothing.
                assertEquals(HttpStatusCode.OK, uploadTo(first).status)
                assertEquals("private", scalar("SELECT visibility FROM activities WHERE client_activity_id = '$first'"))
                assertEquals(
                    HttpStatusCode.UnprocessableEntity,
                    setProfile(alice, "alice") { copy(defaultVisibility = "link") }.status,
                    "a link needs a token made per activity",
                )
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

    private val uploadedBytes = mutableMapOf<UUID, ByteArray>()

    // ---- follow and block ----

    @Test
    fun followNeedsApprovalAndEveryStepHasItsEffect() =
        app {
            val aliceId = idOf(alice)
            val bobId = idOf(bob)

            suspend fun relation() =
                json
                    .decodeFromString(
                        UserCard.serializer(),
                        get("/v1/users/$aliceId") { bearerAuth(bob) }.bodyAsText(),
                    ).relation

            assertEquals(
                HttpStatusCode.BadRequest,
                send("POST", "/v1/users/$aliceId/follow", alice).status,
                "not yourself",
            )
            val sent = send("POST", "/v1/users/$aliceId/follow", bob)
            assertEquals("requested", json.decodeFromString(RelationResult.serializer(), sent.bodyAsText()).relation)
            send("POST", "/v1/users/$aliceId/follow", bob) // twice is the same request
            assertEquals(1L, scalar("SELECT count(*) FROM follows"))

            val requests =
                json.decodeFromString(
                    FollowRequests.serializer(),
                    get("/v1/me/follow-requests") {
                        bearerAuth(alice)
                    }.bodyAsText(),
                )
            assertEquals(1, requests.count)
            assertEquals(bobId.toString(), requests.items.single().id)
            assertEquals(
                HttpStatusCode.NotFound,
                send("POST", "/v1/me/follow-requests/$bobId/accept", bob).status,
                "only the followed accepts",
            )

            assertEquals(HttpStatusCode.NoContent, send("POST", "/v1/me/follow-requests/$bobId/decline", alice).status)
            assertEquals("none", relation())
            send("POST", "/v1/users/$aliceId/follow", bob)
            assertEquals(HttpStatusCode.NoContent, send("POST", "/v1/me/follow-requests/$bobId/accept", alice).status)
            assertEquals("following", relation())
            assertEquals(
                0,
                json
                    .decodeFromString(
                        FollowRequests.serializer(),
                        get("/v1/me/follow-requests") {
                            bearerAuth(alice)
                        }.bodyAsText(),
                    ).count,
            )

            val followers =
                json.decodeFromString(
                    ListSerializer(
                        UserRef.serializer(),
                    ),
                    get("/v1/users/$aliceId/followers") {
                        bearerAuth(alice)
                    }.bodyAsText(),
                )
            assertEquals(listOf(bobId.toString()), followers.map { it.id })

            assertEquals(HttpStatusCode.NoContent, send("DELETE", "/v1/me/followers/$bobId", alice).status)
            assertEquals("none", relation())
            send("POST", "/v1/users/$aliceId/follow", bob)
            assertEquals(HttpStatusCode.NoContent, send("DELETE", "/v1/users/$aliceId/follow", bob).status, "cancel")
            assertEquals(0L, scalar("SELECT count(*) FROM follows"))
        }

    @Test
    fun aBlockCutsTheFollowBothWaysAndHidesThePeopleFromEachOther() =
        app {
            val aliceId = idOf(alice)
            val bobId = idOf(bob)
            befriend(bob, alice)
            befriend(alice, bob)
            val pub = insertActivity(aliceId, Visibilities.PUBLIC)

            assertEquals(HttpStatusCode.NoContent, send("PUT", "/v1/me/blocks/$bobId", alice).status)
            assertEquals(0L, scalar("SELECT count(*) FROM follows"))
            assertEquals(
                HttpStatusCode.NotFound,
                get("/v1/users/$aliceId") { bearerAuth(bob) }.status,
                "bob no longer sees alice",
            )
            assertEquals(HttpStatusCode.NotFound, get("/v1/users/$aliceId/activities/$pub") { bearerAuth(bob) }.status)
            assertEquals(HttpStatusCode.NotFound, send("POST", "/v1/users/$aliceId/follow", bob).status)
            assertEquals(
                HttpStatusCode.NotFound,
                send("POST", "/v1/users/$bobId/follow", alice).status,
                "nor can alice follow bob",
            )
            val card =
                json.decodeFromString(
                    UserCard.serializer(),
                    get("/v1/users/$bobId") { bearerAuth(alice) }.bodyAsText(),
                )
            assertEquals("blocked", card.relation, "alice sees bob as someone she blocked")
            assertEquals(
                listOf(
                    bobId.toString(),
                ),
                json
                    .decodeFromString(
                        ListSerializer(UserRef.serializer()),
                        get("/v1/me/blocks") {
                            bearerAuth(alice)
                        }.bodyAsText(),
                    ).map { it.id },
            )

            assertEquals(HttpStatusCode.NoContent, send("DELETE", "/v1/me/blocks/$bobId", alice).status)
            assertEquals(HttpStatusCode.OK, get("/v1/users/$aliceId") { bearerAuth(bob) }.status)
            assertEquals(0L, scalar("SELECT count(*) FROM follows"), "unblocking does not bring the follow back")
        }

    @Test
    fun aDeletedAccountDisappearsFromEveryoneElsesViewAtOnce() =
        app {
            val aliceId = idOf(alice)
            val bobId = idOf(bob)
            befriend(bob, alice)
            befriend(alice, bob)
            val id = insertActivity(aliceId, Visibilities.PUBLIC)
            send("POST", "/v1/reports", alice, """{"targetUserId":"$bobId","reason":"spam"}""")
            send("PUT", "/v1/me/blocks/${idOf(carol)}", alice)

            assertEquals(HttpStatusCode.NoContent, send("DELETE", "/v1/me", alice).status)

            assertEquals(HttpStatusCode.NotFound, get("/v1/users/$aliceId") { bearerAuth(bob) }.status)
            assertEquals(HttpStatusCode.NotFound, get("/v1/users/$aliceId/activities/$id") { bearerAuth(bob) }.status)
            val bobFollowing =
                json.decodeFromString(
                    ListSerializer(
                        UserRef.serializer(),
                    ),
                    get("/v1/users/$bobId/following") {
                        bearerAuth(bob)
                    }.bodyAsText(),
                )
            assertEquals(emptyList(), bobFollowing)
            assertEquals(0L, scalar("SELECT count(*) FROM follows"))
            assertEquals(0L, scalar("SELECT count(*) FROM blocks"))
            assertEquals(1L, scalar("SELECT count(*) FROM reports"), "the report stays")
            assertNull(scalar("SELECT reporter_id FROM reports"), "but it forgets who made it")
        }

    // ---- reports ----

    @Test
    fun aReportNeedsATargetTheReporterCanSeeAndIsNotDuplicated() =
        app {
            val aliceId = idOf(alice)
            val bobId = idOf(bob)
            val hidden = insertActivity(aliceId, Visibilities.PRIVATE)
            val shown = insertActivity(aliceId, Visibilities.PUBLIC)

            suspend fun report(body: String) = send("POST", "/v1/reports", bob, body)

            assertEquals(
                HttpStatusCode.Created,
                report("""{"targetUserId":"$aliceId","reason":"spam","note":"x"}""").status,
            )
            assertEquals(HttpStatusCode.Created, report("""{"targetUserId":"$aliceId","reason":"spam"}""").status)
            assertEquals(1L, scalar("SELECT count(*) FROM reports"), "the same report twice is one")
            assertEquals(
                HttpStatusCode.Created,
                report("""{"targetUserId":"$aliceId","targetActivityId":"$shown","reason":"privacy"}""").status,
            )
            assertEquals(2L, scalar("SELECT count(*) FROM reports"))
            assertEquals(
                HttpStatusCode.NotFound,
                report("""{"targetUserId":"$aliceId","targetActivityId":"$hidden","reason":"privacy"}""").status,
            )
            assertEquals(
                HttpStatusCode.UnprocessableEntity,
                report("""{"targetUserId":"$aliceId","reason":"because"}""").status,
            )
            assertEquals(
                HttpStatusCode.UnprocessableEntity,
                report("""{"targetUserId":"$bobId","reason":"spam"}""").status,
                "not yourself",
            )
            assertEquals(
                HttpStatusCode.NotFound,
                report("""{"targetUserId":"${UUID.randomUUID()}","reason":"spam"}""").status,
            )
            assertEquals(
                HttpStatusCode.Unauthorized,
                send("POST", "/v1/reports", null, """{"targetUserId":"$aliceId","reason":"spam"}""").status,
            )
        }

    // ---- avatar ----

    private fun jpegWithExif(): ByteArray {
        val image = BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB)
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "jpg", out)
        val plain = out.toByteArray()
        // An APP1 segment right after SOI that carries a marker we can search for: stands for EXIF with GPS.
        val payload = "Exif\u0000\u0000GPS-LATITUDE-45.464".toByteArray()
        val length = payload.size + 2
        val segment = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + payload
        return plain.copyOfRange(0, 2) + segment + plain.copyOfRange(2, plain.size)
    }

    @Test
    fun anAvatarIsRebuiltFromPixelsOnlyAndSeenByThoseWhoMaySeeIt() =
        app {
            val aliceId = idOf(alice)
            idOf(bob)
            val original = jpegWithExif()
            assertTrue(String(original, Charsets.ISO_8859_1).contains("GPS-LATITUDE"))

            val up =
                put("/v1/me/avatar") {
                    bearerAuth(alice)
                    setBody(original)
                }
            assertEquals(HttpStatusCode.OK, up.status)
            assertTrue(json.decodeFromString(SocialProfile.serializer(), up.bodyAsText()).hasAvatar)

            val served = get("/v1/users/$aliceId/avatar") { bearerAuth(bob) }
            assertEquals(HttpStatusCode.OK, served.status)
            val bytes = served.bodyAsBytes()
            val text = String(bytes, Charsets.ISO_8859_1)
            assertFalse(text.contains("GPS-LATITUDE") || text.contains("Exif"), "no metadata survives")
            val decoded = ImageIO.read(ByteArrayInputStream(bytes))
            assertEquals(256, decoded.width)
            assertEquals(256, decoded.height)

            assertEquals(
                HttpStatusCode.UnprocessableEntity,
                put("/v1/me/avatar") {
                    bearerAuth(alice)
                    setBody("this is not a picture".toByteArray())
                }.status,
            )
            // A second avatar replaces the first object.
            val before = scalar("SELECT avatar_key FROM user_profiles WHERE owner_id = '$aliceId'") as String
            val second =
                BufferedImage(
                    300,
                    300,
                    BufferedImage.TYPE_INT_ARGB,
                ).also { it.setRGB(5, 5, 0x80FF0000.toInt()) }
            val png = ByteArrayOutputStream().also { ImageIO.write(second, "png", it) }.toByteArray()
            assertEquals(
                HttpStatusCode.OK,
                put("/v1/me/avatar") {
                    bearerAuth(alice)
                    setBody(png)
                }.status,
            )
            val after = scalar("SELECT avatar_key FROM user_profiles WHERE owner_id = '$aliceId'") as String
            assertTrue(before != after)
            assertFalse(rawExists(before), "the old object is gone")
            assertTrue(rawExists(after))

            send("PUT", "/v1/me/blocks/${idOf(bob)}", alice)
            assertEquals(HttpStatusCode.NotFound, get("/v1/users/$aliceId/avatar") { bearerAuth(bob) }.status)
            assertEquals(HttpStatusCode.NoContent, send("DELETE", "/v1/me/avatar", alice).status)
            assertFalse(rawExists(after))
        }

    private fun rawExists(key: String) = raw.exists(key)
}
