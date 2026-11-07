package dev.averyn.sync

import dev.averyn.domain.LocationSample
import dev.averyn.domain.Reason
import dev.averyn.domain.Sport
import dev.averyn.sensors.RrInterval
import dev.averyn.tracking.ActivityMetadata
import dev.averyn.tracking.ActivityRecorder
import dev.averyn.tracking.ActivityStore
import dev.averyn.tracking.SynapseSidecarKind
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActivitySyncTest {
    private lateinit var dir: Path
    private lateinit var store: ActivityStore
    private lateinit var statuses: SyncStatusStore
    private val requests = mutableListOf<HttpRequestData>()

    @BeforeTest
    fun setUp() {
        dir = createTestDir("averyn-sync-test")
        store = ActivityStore(dir)
        statuses = SyncStatusStore(store)
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun finishedActivity(
        id: String,
        startedAtMs: Long = 1_000,
        fail: Boolean = false,
    ) {
        val recorder = ActivityRecorder(store)
        recorder.start(id, Sport.RUN, 0, startedAtMs)
        recorder.gpsReady(0, startedAtMs)
        recorder.onSample(LocationSample(startedAtMs, 0, 0.0, 0.0, 5.0))
        if (fail) {
            recorder.fail(1_000, startedAtMs + 1_000, Reason.PERMISSION_REVOKED)
        } else {
            recorder.stop(
                1_000,
                startedAtMs + 1_000,
            )
        }
    }

    private fun recordingActivity(id: String) {
        val recorder = ActivityRecorder(store)
        recorder.start(id, Sport.RUN, 0, 5_000)
        recorder.gpsReady(0, 5_000)
    }

    private fun sync(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        ActivitySync(
            store,
            statuses,
            HttpClient(
                MockEngine { request ->
                    requests += request
                    handler(request)
                },
            ),
            "https://server.test/",
        )

    private val created: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = {
        respond("""{"id":"srv-1"}""", HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private fun failing(status: HttpStatusCode): suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
        {
            respond("", status)
        }

    @Test
    fun uploadsAFinishedActivityExactlyAsStoredAndMarksItReady() =
        runTest {
            finishedActivity("a1")

            val result = sync(created).syncPending("tok")

            assertEquals(SyncRunResult(uploaded = 1, retryable = 0, permanent = 0, needsLogin = false), result)
            val request = requests.single()
            assertEquals(HttpMethod.Put, request.method)
            assertEquals("https://server.test/v1/activities/a1", request.url.toString())
            assertEquals("Bearer tok", request.headers[HttpHeaders.Authorization])
            assertEquals("application/x-ndjson", request.body.contentType.toString())
            assertContentEquals(Path(dir, "a1.jsonl").readBytes(), request.body.toByteArray())
            assertEquals(
                SyncStatus(SyncState.READY, attempts = 1, serverActivityId = "srv-1"),
                statuses.statusOf("a1"),
            )
        }

    @Test
    fun aReadyActivityIsNeverUploadedAgain() =
        runTest {
            finishedActivity("a1")
            sync(created).syncPending("tok")
            requests.clear()

            val result = sync(created).syncPending("tok")

            assertEquals(0, result.uploaded)
            assertTrue(requests.isEmpty())
        }

    @Test
    fun theHeartRateSidecarGoesUpAfterItsActivity() =
        runTest {
            finishedActivity("a1")
            store.ensureRrMetadata(ActivityMetadata("a1", Sport.RUN, 1_000))
            store.appendRrInterval("a1", RrInterval(1_000, 1_000, 857))

            sync(created).syncPending("tok")

            val sidecar = requests.last()
            assertEquals("https://server.test/v1/activities/a1/rr", sidecar.url.toString())
            assertEquals(
                SyncStatus(SyncState.READY, attempts = 1),
                statuses.readRr("a1"),
            )
        }

    @Test
    fun aSidecarThatFailedRetryablyIsTriedAgainOnTheNextPass() =
        runTest {
            finishedActivity("a1")
            store.ensureRrMetadata(ActivityMetadata("a1", Sport.RUN, 1_000))
            store.appendRrInterval("a1", RrInterval(1_000, 1_000, 857))

            fun answer(rrStatus: HttpStatusCode): suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
                { request ->
                    if (request.url.toString().endsWith("/rr")) {
                        respond("", rrStatus)
                    } else {
                        respond("""{"id":"srv-1"}""", HttpStatusCode.Created)
                    }
                }

            // The activity goes up, the sidecar meets a server error: the pass says "try again".
            val first = sync(answer(HttpStatusCode.ServiceUnavailable)).syncPending("tok")
            assertEquals(1, first.uploaded)
            assertEquals(1, first.retryable)
            assertEquals(SyncState.FAILED_RETRYABLE, statuses.readRr("a1")?.state)

            // The activity is READY now; the sidecar must still get its second chance.
            val second = sync(answer(HttpStatusCode.Created)).syncPending("tok")
            assertEquals(0, second.retryable)
            assertEquals(SyncState.READY, statuses.readRr("a1")?.state)
        }

    @Test
    fun aRefusedTokenOnTheSidecarAsksForLogin() =
        runTest {
            finishedActivity("a1")
            store.ensureRrMetadata(ActivityMetadata("a1", Sport.RUN, 1_000))
            store.appendRrInterval("a1", RrInterval(1_000, 1_000, 857))

            val result =
                sync { request ->
                    if (request.url.toString().endsWith("/rr")) {
                        respond("", HttpStatusCode.Unauthorized)
                    } else {
                        respond("""{"id":"srv-1"}""", HttpStatusCode.Created)
                    }
                }.syncPending("tok")

            assertEquals(true, result.needsLogin)
        }

    @Test
    fun aRefusedSidecarIsNotUploadedAgainAndTheLocalCopyGoes() =
        runTest {
            finishedActivity("a1")
            store.ensureRrMetadata(ActivityMetadata("a1", Sport.RUN, 1_000))
            store.appendRrInterval("a1", RrInterval(1_000, 1_000, 857))

            // The consent was withdrawn: the server refuses the health data and it must not come back.
            val result =
                sync { request ->
                    if (request.url.toString().endsWith("/rr")) {
                        respond("", HttpStatusCode.Forbidden)
                    } else {
                        respond(
                            """{"id":"srv-1"}""",
                            HttpStatusCode.Created,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }.syncPending("tok")

            assertEquals(1, result.uploaded)
            assertEquals(
                SyncStatus(SyncState.FAILED_PERMANENT, attempts = 1, lastError = "CONSENT_REVOKED"),
                statuses.readRr("a1"),
            )
            assertNull(store.rrMetadataOf("a1"))
        }

    @Test
    fun serverAndNetworkTroubleIsRetriedLaterThenSucceeds() =
        runTest {
            finishedActivity("a1")

            assertEquals(1, sync(failing(HttpStatusCode.InternalServerError)).syncPending("tok").retryable)
            assertEquals(SyncStatus(SyncState.FAILED_RETRYABLE, 1, "HTTP_500"), statuses.statusOf("a1"))

            assertEquals(1, sync(failing(HttpStatusCode.TooManyRequests)).syncPending("tok").retryable)
            assertEquals("HTTP_429", statuses.statusOf("a1").lastError)

            assertEquals(1, sync { throw IOException("no route to host") }.syncPending("tok").retryable)
            assertEquals(SyncStatus(SyncState.FAILED_RETRYABLE, 3, "NETWORK"), statuses.statusOf("a1"))

            assertEquals(1, sync(created).syncPending("tok").uploaded)
            assertEquals(SyncState.READY, statuses.statusOf("a1").state)
            assertEquals(4, statuses.statusOf("a1").attempts)
        }

    @Test
    fun aRejectedActivityIsPermanentAndNotRetried() =
        runTest {
            finishedActivity("a1")
            finishedActivity("a2", startedAtMs = 2_000)

            val result = sync(failing(HttpStatusCode.UnprocessableEntity)).syncPending("tok")

            assertEquals(SyncRunResult(uploaded = 0, retryable = 0, permanent = 2, needsLogin = false), result)
            assertEquals(SyncStatus(SyncState.FAILED_PERMANENT, 1, "HTTP_422"), statuses.statusOf("a1"))
            requests.clear()
            sync(created).syncPending("tok")
            assertTrue(requests.isEmpty())
        }

    @Test
    fun aRefusedTokenStopsTheRunAndAsksForLogin() =
        runTest {
            finishedActivity("a1")
            finishedActivity("a2", startedAtMs = 2_000)

            val result = sync(failing(HttpStatusCode.Unauthorized)).syncPending("expired")

            assertEquals(SyncRunResult(uploaded = 0, retryable = 1, permanent = 0, needsLogin = true), result)
            assertEquals(1, requests.size) // the second activity was not even tried
            assertEquals(SyncStatus(SyncState.FAILED_RETRYABLE, 1, "AUTH"), statuses.statusOf("a1"))
            assertEquals(SyncState.QUEUED, statuses.statusOf("a2").state)
        }

    @Test
    fun withoutATokenNothingIsSentAndNothingChanges() =
        runTest {
            finishedActivity("a1")

            val result = sync(created).syncPending(null)

            assertEquals(SyncRunResult(0, 0, 0, needsLogin = true), result)
            assertTrue(requests.isEmpty())
            assertEquals(SyncState.QUEUED, statuses.statusOf("a1").state)
        }

    @Test
    fun onlyFinishedActivitiesAreUploadedAndFailedOnesKeepTheirData() =
        runTest {
            recordingActivity("running")
            finishedActivity("failed", fail = true)

            val result = sync(created).syncPending("tok")

            assertEquals(1, result.uploaded)
            assertEquals(listOf("https://server.test/v1/activities/failed"), requests.map { it.url.toString() })
            assertEquals(SyncState.LOCAL_ONLY, statuses.statusOf("running").state)
        }

    @Test
    fun theOldestActivityGoesFirst() =
        runTest {
            finishedActivity("newer", startedAtMs = 9_000)
            finishedActivity("older", startedAtMs = 1_000)

            sync(created).syncPending("tok")

            assertEquals(listOf("older", "newer"), requests.map { it.url.toString().substringAfterLast('/') })
        }

    @Test
    fun anUploadInterruptedByAKillIsQueuedAgain() =
        runTest {
            finishedActivity("a1")
            statuses.write("a1", SyncStatus(SyncState.UPLOADING, attempts = 1))
            assertEquals(SyncState.QUEUED, statuses.statusOf("a1").state)

            assertEquals(1, sync(created).syncPending("tok").uploaded)
            assertEquals(2, statuses.statusOf("a1").attempts)
        }

    @Test
    fun anUnreadableStatusFileJustMeansUploadAgain() =
        runTest {
            finishedActivity("a1")
            Path(dir, "a1.sync.json").writeText("{\"state\":")
            assertNull(statuses.read("a1"))

            assertEquals(1, sync(created).syncPending("tok").uploaded)
        }

    @Test
    fun aRateLimitedServerIsNotAskedAgainForEveryOtherActivity() =
        runTest {
            finishedActivity("a1", startedAtMs = 1_000)
            finishedActivity("a2", startedAtMs = 2_000)

            val result = sync(failing(HttpStatusCode.TooManyRequests)).syncPending("tok")

            assertEquals(1, requests.size)
            assertEquals(1, result.retryable)
            assertNull(statuses.read("a2")) // untouched: the next pass picks it up
            requests.clear()
            assertEquals(2, sync(created).syncPending("tok").uploaded)
        }

    @Test
    fun aServerWithNoUploadSlotLeftStopsThePassToo() =
        runTest {
            finishedActivity("a1", startedAtMs = 1_000)
            finishedActivity("a2", startedAtMs = 2_000)

            sync(failing(HttpStatusCode.ServiceUnavailable)).syncPending("tok")

            assertEquals(1, requests.size)
            assertEquals("HTTP_503", statuses.statusOf("a1").lastError)
        }

    @Test
    fun aDeletedAccountIsPermanentWithItsOwnReasonAndStopsThePass() =
        runTest {
            finishedActivity("a1", startedAtMs = 1_000)
            finishedActivity("a2", startedAtMs = 2_000)

            val result = sync(failing(HttpStatusCode.Forbidden)).syncPending("tok")

            assertEquals(1, requests.size)
            assertEquals(1, result.permanent)
            assertEquals(SyncStatus(SyncState.FAILED_PERMANENT, 1, "ACCOUNT_DELETED"), statuses.statusOf("a1"))
        }

    @Test
    fun anActivityDeletedOnTheServerIsPermanentButTheOthersStillUpload() =
        runTest {
            finishedActivity("a1", startedAtMs = 1_000)
            finishedActivity("a2", startedAtMs = 2_000)

            val result =
                sync { request ->
                    if (request.url.encodedPath.endsWith("/a1")) {
                        respond("", HttpStatusCode.Gone)
                    } else {
                        created(this, request)
                    }
                }.syncPending("tok")

            assertEquals(SyncRunResult(uploaded = 1, retryable = 0, permanent = 1, needsLogin = false), result)
            assertEquals(SyncStatus(SyncState.FAILED_PERMANENT, 1, "DELETED_ON_SERVER"), statuses.statusOf("a1"))
            assertEquals(SyncState.READY, statuses.statusOf("a2").state)
        }

    private fun withSynapse(
        id: String,
        kind: SynapseSidecarKind,
    ) {
        finishedActivity(id)
        store.appendSynapse(id, kind, byteArrayOf(1, 2, 3))
    }

    private fun synapseAnswer(
        code: HttpStatusCode,
    ): suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
        { request ->
            if ("/synapse/" in request.url.encodedPath) respond("", code) else created(this, request)
        }

    @Test
    fun theFeatureSidecarGoesUpAsRawBytesAndTheServersNoContentIsSuccess() =
        runTest {
            withSynapse("a1", SynapseSidecarKind.FEATURES)

            val result = sync(synapseAnswer(HttpStatusCode.NoContent)).syncPending("tok")

            val put = requests.last()
            assertEquals(HttpMethod.Put, put.method)
            assertEquals("https://server.test/v1/activities/a1/synapse/features", put.url.toString())
            assertContentEquals(byteArrayOf(1, 2, 3), put.body.toByteArray())
            assertEquals(SyncStatus(SyncState.READY, 1), statuses.readSynapse("a1", SynapseSidecarKind.FEATURES))
            assertEquals(SyncRunResult(uploaded = 1, retryable = 0, permanent = 0, needsLogin = false), result)
        }

    @Test
    fun theRawSidecarStaysOnTheDeviceUntilRawUploadsAreAllowed() =
        runTest {
            withSynapse("a1", SynapseSidecarKind.RAW)

            val s = sync(synapseAnswer(HttpStatusCode.NoContent))
            s.syncPending("tok")
            assertTrue(requests.none { "/synapse/" in it.url.encodedPath }, "not on mobile data by default")
            assertNull(statuses.readSynapse("a1", SynapseSidecarKind.RAW))

            s.rawUploads = true
            s.syncPending("tok")
            assertEquals("https://server.test/v1/activities/a1/synapse/raw", requests.last().url.toString())
            assertEquals(SyncState.READY, statuses.readSynapse("a1", SynapseSidecarKind.RAW)?.state)
        }

    @Test
    fun aRefusalForConsentOrDeletionIsPermanentAndTakesTheLocalCopyWithIt() =
        runTest {
            withSynapse("a1", SynapseSidecarKind.FEATURES)
            sync(synapseAnswer(HttpStatusCode.Forbidden)).syncPending("tok")
            assertEquals(
                SyncStatus(SyncState.FAILED_PERMANENT, 1, "CONSENT_REVOKED"),
                statuses.readSynapse("a1", SynapseSidecarKind.FEATURES),
            )
            assertFalse(store.hasSynapse("a1", SynapseSidecarKind.FEATURES))

            withSynapse("a2", SynapseSidecarKind.FEATURES)
            sync(synapseAnswer(HttpStatusCode.Gone)).syncPending("tok")
            assertEquals("DELETED_ON_SERVER", statuses.readSynapse("a2", SynapseSidecarKind.FEATURES)?.lastError)
            assertFalse(store.hasSynapse("a2", SynapseSidecarKind.FEATURES))
        }

    @Test
    fun everyOtherCodeIsRetriedOrRefusedForGoodAsTheTableSays() =
        runTest {
            val expected =
                mapOf(
                    HttpStatusCode.NotFound to SyncState.FAILED_RETRYABLE,
                    HttpStatusCode.InsufficientStorage to SyncState.FAILED_RETRYABLE,
                    HttpStatusCode.TooManyRequests to SyncState.FAILED_RETRYABLE,
                    HttpStatusCode.PayloadTooLarge to SyncState.FAILED_PERMANENT,
                    HttpStatusCode.UnprocessableEntity to SyncState.FAILED_PERMANENT,
                )
            for ((code, state) in expected) {
                val id = "a${code.value}"
                withSynapse(id, SynapseSidecarKind.FEATURES)
                sync(synapseAnswer(code)).syncPending("tok")
                assertEquals(state, statuses.readSynapse(id, SynapseSidecarKind.FEATURES)?.state, "HTTP ${code.value}")
                assertTrue(
                    store.hasSynapse(id, SynapseSidecarKind.FEATURES),
                    "kept: only a consent or deletion drops it",
                )
            }
        }

    @Test
    fun aSidecarThatFailedRetryablyIsTriedAgainAndANetworkFailureCountsAsRetry() =
        runTest {
            withSynapse("a1", SynapseSidecarKind.FEATURES)
            val down =
                sync { request ->
                    if ("/synapse/" in
                        request.url.encodedPath
                    ) {
                        throw IOException("no network")
                    } else {
                        created(this, request)
                    }
                }
            val first = down.syncPending("tok")
            assertEquals(1, first.retryable)
            assertEquals("NETWORK", statuses.readSynapse("a1", SynapseSidecarKind.FEATURES)?.lastError)

            val second = sync(synapseAnswer(HttpStatusCode.NoContent)).syncPending("tok")
            assertEquals(SyncState.READY, statuses.readSynapse("a1", SynapseSidecarKind.FEATURES)?.state)
            assertEquals(0, second.retryable)
        }

    @Test
    fun aRefusedTokenOnASidecarAsksForLogin() =
        runTest {
            withSynapse("a1", SynapseSidecarKind.FEATURES)
            val result = sync(synapseAnswer(HttpStatusCode.Unauthorized)).syncPending("tok")
            assertTrue(result.needsLogin)
        }
}
