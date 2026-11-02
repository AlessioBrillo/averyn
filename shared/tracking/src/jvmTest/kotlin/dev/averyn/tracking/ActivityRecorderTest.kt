package dev.averyn.tracking

import dev.averyn.domain.ActivityState
import dev.averyn.domain.LocationSample
import dev.averyn.domain.Reason
import dev.averyn.domain.Sport
import dev.averyn.domain.StateEvent
import dev.averyn.sensors.HR_ALGORITHM_VERSION
import dev.averyn.sensors.HeartRateSample
import kotlinx.io.files.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ActivityRecorderTest {
    private lateinit var dir: java.nio.file.Path
    private lateinit var store: ActivityStore

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("averyn-recorder-test")
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

    @Test
    fun happyPathStartRecordPauseResumeStop() {
        val recorder = ActivityRecorder(store)
        recorder.start("a1", Sport.RUN, 0, 0)
        assertEquals(ActivityState.PREPARING, recorder.currentState)
        recorder.gpsReady(100, 100)
        assertEquals(ActivityState.RECORDING, recorder.currentState)

        val step = 100.0 / 111_195.08
        recorder.onSample(sample(100, 0.0))
        recorder.onSample(sample(10_100, step))

        recorder.pause(10_100, 10_100)
        assertEquals(ActivityState.PAUSED, recorder.currentState)
        recorder.resume(70_100, 70_100)
        assertEquals(ActivityState.RECORDING, recorder.currentState)

        recorder.onSample(sample(70_100, step))
        recorder.onSample(sample(80_100, 2 * step))

        val completed = recorder.stop(80_100, 80_100)
        assertEquals(ActivityState.COMPLETED, recorder.currentState)
        assertEquals(200.0, completed.metrics.distanceM, 1.0)
        assertEquals(60_000L, completed.metrics.pausedMs)
    }

    @Test
    fun illegalTransitionThrows() {
        val recorder = ActivityRecorder(store)
        assertFailsWith<IllegalStateException> { recorder.pause(0, 0) } // IDLE -> PAUSED is not legal
    }

    @Test
    fun onSampleDuringPausedIsStoredButNotUsedForMetrics() {
        val recorder = ActivityRecorder(store)
        recorder.start("a1", Sport.RUN, 0, 0)
        recorder.gpsReady(0, 0)
        recorder.onSample(sample(0, 0.0))
        recorder.pause(1_000, 1_000)
        recorder.onSample(sample(2_000, 1.0)) // arrives while PAUSED: stored, not metered

        var sampleCount = 0
        store.forEachRecord("a1") { record -> if (record is ActivityRecord.Sample) sampleCount++ }
        assertEquals(2, sampleCount) // both raw samples are durable (F5): nothing silently lost
    }

    @Test
    fun failMovesToFailedAndKeepsData() {
        val recorder = ActivityRecorder(store)
        recorder.start("a1", Sport.RUN, 0, 0)
        recorder.gpsReady(0, 0)
        recorder.onSample(sample(0, 0.0))
        recorder.fail(1_000, 1_000, Reason.PERMISSION_REVOKED)
        assertEquals(ActivityState.FAILED, recorder.currentState)

        var sampleCount = 0
        store.forEachRecord("a1") { record -> if (record is ActivityRecord.Sample) sampleCount++ }
        assertEquals(1, sampleCount) // FAILED never means data discarded (TDD-0001 §5.2)
    }

    @Test
    fun recoverResumesAnInterruptedRecordingActivity() {
        val original = ActivityRecorder(store)
        original.start("a1", Sport.RUN, 0, 0)
        original.gpsReady(0, 0)
        val step = 100.0 / 111_195.08
        original.onSample(sample(0, 0.0))
        original.onSample(sample(10_000, step))
        // No stop() call: simulates a kill while RECORDING.

        assertEquals(listOf("a1"), store.interrupted().map { it.activityId })

        val fresh = ActivityRecorder(store)
        val result = fresh.recover("a1", 20_000, 20_000)
        assertTrue(result is RecoveryResult.Resumed)
        assertEquals(100.0, result.snapshot.metrics.distanceM, 1.0)
        assertEquals(ActivityState.RECORDING, fresh.currentState)

        // The recorder is live again: further samples and a normal stop work as usual.
        fresh.onSample(sample(20_000, 2 * step))
        val completed = fresh.stop(30_000, 30_000)
        assertEquals(200.0, completed.metrics.distanceM, 1.0)
    }

    @Test
    fun theDroppedInputCountsSurviveAKill() {
        val original = ActivityRecorder(store)
        original.start("a1", Sport.RUN, 0, 0)
        original.gpsReady(0, 0)
        original.recordDroppedInput(DroppedInputReason.INVALID_HEART_RATE)
        original.recordDroppedInput(DroppedInputReason.INVALID_SAMPLE)
        // No stop(): a kill. The counters were written when each reason first appeared.

        // What a history export of that activity says, from a process that never saw the recorder:
        assertEquals(
            mapOf(DroppedInputReason.INVALID_HEART_RATE to 1, DroppedInputReason.INVALID_SAMPLE to 1),
            store.diagnosticsOf("a1")?.droppedInputs,
        )

        // A fix that arrived before the recovery (no activity yet) is added to the ones from before the kill.
        val fresh = ActivityRecorder(store)
        fresh.recordDroppedInput(DroppedInputReason.INVALID_SAMPLE)
        fresh.recover("a1", 10_000, 10_000)
        val diagnostics = fresh.stop(20_000, 20_000).diagnostics
        assertEquals(2, diagnostics.droppedInputs[DroppedInputReason.INVALID_SAMPLE])
        assertEquals(1, diagnostics.droppedInputs[DroppedInputReason.INVALID_HEART_RATE])
    }

    @Test
    fun theDroppedInputCountsGoWithTheActivity() {
        val recorder = ActivityRecorder(store)
        recorder.start("a1", Sport.RUN, 0, 0)
        recorder.recordDroppedInput(DroppedInputReason.INVALID_SAMPLE)
        assertEquals(1, store.readDroppedInputs("a1").size)

        store.delete("a1")

        assertEquals(emptyMap(), store.readDroppedInputs("a1"))
    }

    @Test
    fun aRecoveredActivityKeepsItsHeartRateAndANewOneStartsWithout() {
        val original = ActivityRecorder(store)
        original.start("a1", Sport.RUN, 0, 0)
        original.gpsReady(0, 0)
        original.onSample(sample(0, 0.0))
        original.onHeartRate(HeartRateSample(1_000, 1_000, 140))

        val fresh = ActivityRecorder(store)
        fresh.recover("a1", 5_000, 5_000)
        val recovered = fresh.stop(6_000, 6_000).diagnostics
        assertEquals(HR_ALGORITHM_VERSION, recovered.algorithms.heartRate)

        // The same recorder serves the next activity: it has no heart rate, and must not be stamped with one.
        fresh.start("a2", Sport.RUN, 10_000, 10_000)
        fresh.gpsReady(10_000, 10_000)
        fresh.onSample(sample(10_000, 0.0))
        assertEquals(
            null,
            fresh
                .stop(11_000, 11_000)
                .diagnostics.algorithms.heartRate,
        )
    }

    @Test
    fun recoverFinalizesAnActivityKilledWhileStopping() {
        val original = ActivityRecorder(store)
        original.start("a1", Sport.RUN, 0, 0)
        original.gpsReady(0, 0)
        original.onSample(sample(0, 0.0))
        original.onSample(sample(10_000, 100.0 / 111_195.08))
        // Manually append a STOPPING event without COMPLETED, simulating a kill mid-finalize.
        store.appendEvent("a1", StateEvent(10_000, 10_000, ActivityState.STOPPING, Reason.USER))

        val fresh = ActivityRecorder(store)
        val result = fresh.recover("a1", 20_000, 20_000)
        assertTrue(result is RecoveryResult.Completed)
        assertEquals(100.0, result.activity.metrics.distanceM, 1.0)
    }
}
