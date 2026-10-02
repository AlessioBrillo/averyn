package dev.averyn.tracking

import dev.averyn.domain.ActivityState
import dev.averyn.domain.LocationSample
import dev.averyn.domain.Reason
import dev.averyn.domain.Sport
import kotlinx.io.files.Path
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ActivityReplayTest {
    private lateinit var dir: java.nio.file.Path
    private lateinit var store: ActivityStore

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("averyn-replay-test")
        store = ActivityStore(Path(dir.toString()))
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private val step = 100.0 / 111_195.08 // ~100 m of latitude

    private fun sample(
        elapsedMs: Long,
        lat: Double,
        timeMs: Long = elapsedMs,
    ) = LocationSample(timeMs, elapsedMs, lat, 0.0, 5.0)

    /** Records a run with a pause, a duplicate and an out-of-order sample (both flagged), then stops: the device-side numbers. */
    private fun recordFinishedActivity(
        recorder: ActivityRecorder,
        id: String,
    ): CompletedActivity {
        recorder.start(id, Sport.RUN, 0, 0)
        recorder.gpsReady(0, 0)
        recorder.onSample(sample(0, 0.0))
        recorder.onSample(sample(10_000, step))
        recorder.onSample(sample(11_000, step, timeMs = 10_000)) // same position and wall time: DUPLICATE
        recorder.onSample(sample(20_000, 2 * step))
        recorder.onSample(sample(19_500, 2 * step)) // elapsed goes backwards: NON_MONOTONIC_TIME
        recorder.pause(22_000, 22_000)
        recorder.resume(62_000, 62_000)
        recorder.onSample(sample(63_000, 3 * step))
        recorder.onSample(sample(73_000, 4 * step))
        return recorder.stop(73_000, 73_000)
    }

    @Test
    fun replayOfACompletedActivityEqualsWhatTheDeviceComputedLive() {
        val completed = recordFinishedActivity(ActivityRecorder(store), "a1")

        val replayed = replay(store, "a1")

        assertEquals(ActivityState.COMPLETED, replayed.lastState)
        assertEquals(completed.metrics, replayed.metrics.snapshot())
        assertEquals(completed.quality, replayed.quality.build())
        assertEquals(40_000L, replayed.metrics.snapshot().pausedMs) // the pause is closed, as stop() left it
        assertEquals(emptyMap(), replayed.recordLosses)
    }

    @Test
    fun onMeteredSampleSeesOnlySamplesThatCountedTowardsMetrics() {
        recordFinishedActivity(ActivityRecorder(store), "a1")
        val metered = mutableListOf<LocationSample>()

        replay(store, "a1") { metered += it }

        // 7 raw samples were recorded while RECORDING; the duplicate and the out-of-order one are excluded.
        assertEquals(listOf(0L, 10_000L, 20_000L, 63_000L, 73_000L), metered.map { it.elapsedRealtimeMs })
    }

    @Test
    fun replayNeverWritesToTheStore() {
        recordFinishedActivity(ActivityRecorder(store), "a1")
        val file = File(dir.toFile(), "a1.jsonl")
        val before = file.readBytes()

        replay(store, "a1")

        assertContentEquals(before, file.readBytes())
    }

    @Test
    fun aTruncatedLastLineIsCountedNotGuessedAt() {
        recordFinishedActivity(ActivityRecorder(store), "a1")
        File(dir.toFile(), "a1.jsonl").appendText("SAMPLE {\"timeMs\":99")

        val replayed = replay(store, "a1")

        assertEquals(mapOf(RecordLossReason.TRUNCATED_RECORD to 1), replayed.recordLosses)
        assertEquals(ActivityState.COMPLETED, replayed.lastState)
    }

    @Test
    fun anInterruptedActivityIsReturnedAsItStood() {
        val original = ActivityRecorder(store)
        original.start("a1", Sport.RIDE, 0, 0)
        original.gpsReady(0, 0)
        original.onSample(sample(0, 0.0))
        original.onSample(sample(10_000, step))

        val replayed = replay(store, "a1")

        assertEquals(ActivityState.RECORDING, replayed.lastState)
        assertEquals(Sport.RIDE, replayed.metadata.sport)
        assertEquals(100.0, replayed.metrics.snapshot().distanceM, 1.0)
        assertEquals(ActivityState.RECORDING, store.lastStateOf("a1"))
    }

    @Test
    fun lastStateOfIsNullWithoutEvents() {
        store.create(ActivityMetadata("a1", Sport.WALK, 0))
        assertNull(store.lastStateOf("a1"))
        assertEquals(ActivityState.IDLE, replay(store, "a1").lastState)
    }

    @Test
    fun replayWithoutMetadataFails() {
        assertFailsWith<IllegalStateException> { replay(store, "missing") }
    }

    @Test
    fun oneRecorderCanRecordSeveralActivitiesInARow() {
        val recorder = ActivityRecorder(store)
        val first = recordFinishedActivity(recorder, "a1")
        assertEquals(ActivityState.COMPLETED, recorder.currentState)

        val second = recordFinishedActivity(recorder, "a2") // used to throw: COMPLETED -> PREPARING

        assertEquals(first.metrics, second.metrics) // nothing carried over from the first activity
        assertEquals(first.quality, second.quality)
        assertEquals(second.metrics, replay(store, "a2").metrics.snapshot())
    }

    @Test
    fun recoverStillResumesFromAReplay() {
        val original = ActivityRecorder(store)
        original.start("a1", Sport.RUN, 0, 0)
        original.gpsReady(0, 0)
        original.onSample(sample(0, 0.0))
        original.onSample(sample(10_000, step))
        original.pause(10_000, 10_000, Reason.PERMISSION_REVOKED)

        val fresh = ActivityRecorder(store)
        val result = fresh.recover("a1", 90_000, 90_000)

        assertEquals(ActivityState.PAUSED, fresh.currentState)
        assertEquals(100.0, (result as RecoveryResult.Resumed).snapshot.metrics.distanceM, 1.0)
        assertEquals(10_000L, fresh.lastKnownElapsedRealtimeMs)
    }
}
