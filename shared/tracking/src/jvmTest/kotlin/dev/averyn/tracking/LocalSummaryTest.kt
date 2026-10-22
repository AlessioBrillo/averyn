package dev.averyn.tracking

import dev.averyn.domain.ActivityState
import dev.averyn.domain.LocationSample
import dev.averyn.domain.Reason
import dev.averyn.domain.Sport
import dev.averyn.domain.StateEvent
import dev.averyn.sensors.HeartRateSample
import kotlinx.io.files.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The local summary of an activity (TDD-0011): the numbers the list shows when the server cannot be reached.
 */
class LocalSummaryTest {
    private lateinit var dir: java.nio.file.Path
    private lateinit var store: ActivityStore

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("averyn-local-summary-test")
        store = ActivityStore(Path(dir.toString()))
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun sample(
        elapsedMs: Long,
        lat: Double,
    ) = LocationSample(elapsedMs, elapsedMs, lat, 0.0, 5.0)

    private fun hr(
        elapsedMs: Long,
        bpm: Int,
    ) = HeartRateSample(elapsedMs, elapsedMs, bpm)

    @Test
    fun `an activity that ends leaves its numbers next to it`() {
        val recorder = ActivityRecorder(store)
        recorder.start("a1", Sport.RUN, 0, 1_000)
        recorder.gpsReady(0, 1_000)
        recorder.onSample(sample(1_000, 45.0))
        recorder.onSample(sample(2_000, 45.001))
        recorder.stop(3_000, 4_000)

        val summary = store.readLocalSummary("a1")
        assertNotNull(summary)
        assertEquals("a1", summary.activityId)
        assertEquals("RUN", summary.sport)
        assertEquals(1_000L, summary.startedAtMs)
        assertTrue(summary.distanceM > 0.0)
        assertEquals(2_000L, summary.elapsedMs)
        assertEquals("distance-v1,time-v1,speed-v1", summary.metricsAlgorithmVersion)
        assertEquals("quality-v2", summary.qualityAlgorithmVersion)
        assertEquals("none", summary.hrSource)
        assertEquals(0, summary.avgHrBpm)
    }

    @Test
    fun `the summary is not an activity of its own`() {
        val recorder = ActivityRecorder(store)
        recorder.start("a1", Sport.RUN, 0, 1_000)
        recorder.gpsReady(0, 1_000)
        recorder.stop(3_000, 4_000)
        assertEquals(listOf("a1"), store.list().map { it.activityId })
    }

    @Test
    fun `the heart rate of the recording state is in the summary`() {
        val recorder = ActivityRecorder(store)
        recorder.start("a1", Sport.RUN, 0, 1_000)
        recorder.gpsReady(0, 1_000)
        recorder.onHeartRate(hr(1_000, 140))
        recorder.onHeartRate(hr(2_000, 160))
        recorder.pause(2_500, 2_500)
        // A sample of a paused activity is stored but is not part of the metric (hr-v2).
        recorder.onHeartRate(hr(3_000, 200))
        recorder.resume(4_000, 4_000)
        recorder.stop(5_000, 6_000)

        val summary = store.readLocalSummary("a1")
        assertNotNull(summary)
        assertEquals(150, summary.avgHrBpm)
        assertEquals(160, summary.maxHrBpm)
        assertEquals("ble-hr", summary.hrSource)
        assertEquals("hr-v2", summary.hrAlgorithmVersion)
    }

    @Test
    fun `an activity that was killed while stopping is finished with its numbers`() {
        val recorder = ActivityRecorder(store)
        recorder.start("a1", Sport.RUN, 0, 1_000)
        recorder.gpsReady(0, 1_000)
        recorder.onSample(sample(1_000, 45.0))
        // The activity is left in STOPPING, as a kill during the finalization leaves it.
        store.appendEvent("a1", StateEvent(2_000, 2_000, ActivityState.STOPPING, Reason.USER))

        val recovered = ActivityRecorder(store).recover("a1", 3_000, 3_000)
        assertTrue(recovered is RecoveryResult.Completed)
        val summary = store.readLocalSummary("a1")
        assertNotNull(summary)
        assertTrue(summary.distanceM >= 0.0)
    }

    @Test
    fun `an activity that failed has no numbers`() {
        val recorder = ActivityRecorder(store)
        recorder.start("a1", Sport.RUN, 0, 1_000)
        recorder.gpsReady(0, 1_000)
        recorder.fail(2_000, 2_000, Reason.PERMISSION_REVOKED)
        assertNull(store.readLocalSummary("a1"))
    }

    @Test
    fun `deleting an activity takes its summary with it`() {
        val recorder = ActivityRecorder(store)
        recorder.start("a1", Sport.RUN, 0, 1_000)
        recorder.gpsReady(0, 1_000)
        recorder.stop(3_000, 4_000)
        assertNotNull(store.readLocalSummary("a1"))
        store.delete("a1")
        assertNull(store.readLocalSummary("a1"))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `the numbers of an activity that ends are the ones a recomputation finds`() {
        val recorder = ActivityRecorder(store)
        recorder.start("a1", Sport.RUN, 0, 1_000)
        recorder.gpsReady(0, 1_000)
        recorder.onSample(sample(1_000, 45.0))
        recorder.onSample(sample(2_000, 45.001))
        recorder.onHeartRate(hr(1_500, 150))
        recorder.stop(3_000, 4_000)

        val live = store.readLocalSummary("a1")
        val recomputed = store.localSummaryOf("a1")
        assertNotNull(live)
        assertNotNull(recomputed)
        assertEquals(live.distanceM, recomputed.distanceM)
        assertEquals(live.elapsedMs, recomputed.elapsedMs)
        assertEquals(live.movingMs, recomputed.movingMs)
        assertEquals(live.qualityGrade, recomputed.qualityGrade)
        assertEquals(live.avgHrBpm, recomputed.avgHrBpm)
        assertEquals(live.maxHrBpm, recomputed.maxHrBpm)
        assertEquals(live.metricsAlgorithmVersion, recomputed.metricsAlgorithmVersion)
    }

    @Test
    fun `the activities of an older build get their numbers back`() {
        val recorder = ActivityRecorder(store)
        recorder.start("a1", Sport.RUN, 0, 1_000)
        recorder.gpsReady(0, 1_000)
        recorder.onSample(sample(1_000, 45.0))
        recorder.stop(3_000, 4_000)
        // The sidecar of an older build is not there: the recomputation writes it.
        store.deleteLocalSummary("a1")
        assertNull(store.readLocalSummary("a1"))

        assertEquals(1, store.backfillLocalSummaries())
        assertNotNull(store.readLocalSummary("a1"))
        // A second pass has nothing to do.
        assertEquals(0, store.backfillLocalSummaries())
    }

    @Test
    fun `an activity that is still recording is left alone`() {
        val recorder = ActivityRecorder(store)
        recorder.start("a1", Sport.RUN, 0, 1_000)
        recorder.gpsReady(0, 1_000)
        assertEquals(0, store.backfillLocalSummaries())
        assertNull(store.readLocalSummary("a1"))
    }
}
