package dev.averyn.tracking

import dev.averyn.domain.ActivityState
import dev.averyn.domain.LocationSample
import dev.averyn.domain.Reason
import dev.averyn.domain.Sport
import dev.averyn.domain.StateEvent
import kotlinx.io.files.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TDD-0006 §5.2/§5.3: whatever happened to an activity (kill, reboot, recovery while paused, "finish now"),
 * the numbers the phone shows live, the numbers after a recovery and the numbers a `replay()` of the stored
 * file produces are the same.
 */
class RecoveryDeterminismTest {
    private lateinit var dir: java.nio.file.Path
    private lateinit var store: ActivityStore

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("averyn-recovery-test")
        store = ActivityStore(Path(dir.toString()))
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private val step = 100.0 / 111_195.08 // ~100 m of latitude
    private val wallBase = 1_000_000_000L

    /** A fix at [elapsedMs] on the boot's monotonic clock; wall time is `wallBootMs + elapsedMs` for that boot. */
    private fun fix(
        elapsedMs: Long,
        steps: Int,
        wallBootMs: Long = wallBase,
    ) = LocationSample(wallBootMs + elapsedMs, elapsedMs, steps * step, 0.0, 5.0)

    private fun events(id: String): List<StateEvent> {
        val out = mutableListOf<StateEvent>()
        store.forEachRecord(id) { if (it is ActivityRecord.Event) out += it.event }
        return out
    }

    private fun assertLiveEqualsReplay(
        live: CompletedActivity,
        id: String,
    ) {
        val replayed = replay(store, id)
        assertEquals(ActivityState.COMPLETED, replayed.lastState)
        assertEquals(live.metrics, replayed.metrics.snapshot())
        assertEquals(live.quality, replayed.quality.build())
    }

    @Test
    fun recoveredWhileRecordingThenFinishedEqualsReplay() {
        val original = ActivityRecorder(store)
        original.start("a1", Sport.RUN, 0, wallBase)
        original.gpsReady(0, wallBase)
        original.onSample(fix(0, 0))
        original.onSample(fix(10_000, 1))
        original.onSample(fix(20_000, 2))

        val fresh = ActivityRecorder(store)
        fresh.recover("a1", 30_000, wallBase + 30_000)
        fresh.onSample(fix(31_000, 3))
        fresh.onSample(fix(41_000, 4))
        val completed = fresh.stop(41_000, wallBase + 41_000)

        assertEquals(400.0, completed.metrics.distanceM, 2.0)
        assertEquals(1, completed.diagnostics.recoveries)
        assertLiveEqualsReplay(completed, "a1")
    }

    @Test
    fun recoveredWhilePausedThenFinishNowDoesNotInventAPause() {
        val original = ActivityRecorder(store)
        original.start("a1", Sport.RUN, 0, wallBase)
        original.gpsReady(0, wallBase)
        original.onSample(fix(0, 0))
        original.onSample(fix(10_000, 1))
        original.pause(12_000, wallBase + 12_000)
        original.onSample(fix(15_000, 1)) // stored while PAUSED: the last thing that happened

        val fresh = ActivityRecorder(store)
        fresh.recover("a1", 500_000, wallBase + 500_000) // relaunched much later, same boot
        assertEquals(ActivityState.PAUSED, fresh.currentState)
        val completed = fresh.stop(fresh.lastKnownElapsedRealtimeMs, fresh.lastKnownTimeMs)

        // The pause ran from its event to the last known time, not to the relaunch and never negative.
        assertEquals(3_000L, completed.metrics.pausedMs)
        assertLiveEqualsReplay(completed, "a1")
    }

    @Test
    fun recoveredWhilePausedThenResumedEqualsReplay() {
        val original = ActivityRecorder(store)
        original.start("a1", Sport.RUN, 0, wallBase)
        original.gpsReady(0, wallBase)
        original.onSample(fix(0, 0))
        original.onSample(fix(10_000, 1))
        original.pause(12_000, wallBase + 12_000, Reason.PERMISSION_REVOKED)

        val fresh = ActivityRecorder(store)
        fresh.recover("a1", 100_000, wallBase + 100_000)
        fresh.resume(100_000, wallBase + 100_000)
        fresh.onSample(fix(101_000, 2))
        fresh.onSample(fix(111_000, 3))
        val completed = fresh.stop(111_000, wallBase + 111_000)

        assertEquals(88_000L, completed.metrics.pausedMs) // 12 s -> 100 s
        assertLiveEqualsReplay(completed, "a1")
    }

    @Test
    fun killedWhileStoppingIsFinalizedAtTheStoppingTimeNotAtRelaunch() {
        val original = ActivityRecorder(store)
        original.start("a1", Sport.RUN, 0, wallBase)
        original.gpsReady(0, wallBase)
        original.onSample(fix(0, 0))
        original.onSample(fix(10_000, 1))
        store.appendEvent("a1", StateEvent(20_000, wallBase + 20_000, ActivityState.STOPPING, Reason.USER))

        val result = ActivityRecorder(store).recover("a1", 900_000, wallBase + 900_000)

        val completed = (result as RecoveryResult.Completed).activity
        assertEquals(20_000L, completed.metrics.elapsedMs)
        assertLiveEqualsReplay(completed, "a1")
        assertEquals(wallBase + 20_000, events("a1").last().timeMs) // COMPLETED carries the activity time
    }

    @Test
    fun aRebootKeepsTheClockContinuous() {
        val boot1 = wallBase - 100_000 // wall = raw + boot1 while the first boot is up
        val original = ActivityRecorder(store)
        original.start("a1", Sport.RUN, 100_000, wallBase)
        original.gpsReady(100_000, wallBase)
        original.onSample(fix(100_000, 0, boot1))
        original.onSample(fix(110_000, 1, boot1))
        original.onSample(fix(120_000, 2, boot1))

        // Killed at raw 120_000 (wall wallBase + 20_000). The phone reboots and the app is relaunched 25 s later:
        // the monotonic clock restarted, so "now" is raw 25_000 while wall time moved on by 25 s.
        val relaunchWall = wallBase + 20_000 + 25_000
        val boot2 = relaunchWall - 25_000
        val fresh = ActivityRecorder(store)
        fresh.recover("a1", 25_000, relaunchWall)
        fresh.onSample(fix(26_000, 3, boot2))
        fresh.onSample(fix(36_000, 4, boot2))
        val completed = fresh.stop(36_000, boot2 + 36_000)

        assertEquals(0, completed.quality.flagCounts.getValue(QualityFlag.NON_MONOTONIC_TIME))
        assertEquals(400.0, completed.metrics.distanceM, 2.0)
        // first fix at wall +0, last fix at wall +56 s: elapsed is real time, the 25 s outage included
        assertEquals(56_000L, completed.metrics.elapsedMs)
        assertEquals(1, completed.diagnostics.recoveries)
        assertLiveEqualsReplay(completed, "a1")

        val recovered = events("a1").single { it.reason == Reason.RECOVERED }
        assertTrue(recovered.clockOffsetMs > 0)
    }

    @Test
    fun twoRebootsAccumulateTheirOffsets() {
        val boot1 = wallBase - 100_000
        val a = ActivityRecorder(store)
        a.start("a1", Sport.RUN, 100_000, wallBase)
        a.gpsReady(100_000, wallBase)
        a.onSample(fix(100_000, 0, boot1))
        a.onSample(fix(110_000, 1, boot1))

        // first reboot: relaunch 20 s after the kill (wall), new boot is 5 s old
        val relaunch1 = wallBase + 10_000 + 20_000
        val boot2 = relaunch1 - 5_000
        val b = ActivityRecorder(store)
        b.recover("a1", 5_000, relaunch1)
        b.onSample(fix(6_000, 2, boot2))
        b.onSample(fix(16_000, 3, boot2))

        // second kill at raw 16_000, second reboot, relaunch 30 s later at raw 8_000
        val relaunch2 = boot2 + 16_000 + 30_000
        val boot3 = relaunch2 - 8_000
        val c = ActivityRecorder(store)
        c.recover("a1", 8_000, relaunch2)
        c.onSample(fix(9_000, 4, boot3))
        val completed = c.stop(9_000, boot3 + 9_000)

        assertEquals(0, completed.quality.flagCounts.getValue(QualityFlag.NON_MONOTONIC_TIME))
        assertEquals(400.0, completed.metrics.distanceM, 2.0)
        assertEquals(2, completed.diagnostics.recoveries)
        assertLiveEqualsReplay(completed, "a1")
    }

    @Test
    fun aRebootIsDetectedEvenWhenTheNewBootAlreadyRanLongerThanTheOldOne() {
        // The first boot was only 15 s old when the activity was killed; the relaunch happens 30 min later
        // when the new boot is 200 s old, so the raw clock went "forward" and only the wall clock tells.
        val boot1 = wallBase - 5_000
        val a = ActivityRecorder(store)
        a.start("a1", Sport.RUN, 5_000, wallBase)
        a.gpsReady(5_000, wallBase)
        a.onSample(fix(5_000, 0, boot1))
        a.onSample(fix(15_000, 1, boot1))

        val relaunchWall = boot1 + 15_000 + 1_800_000
        val boot2 = relaunchWall - 200_000
        val b = ActivityRecorder(store)
        b.recover("a1", 200_000, relaunchWall)
        b.onSample(fix(201_000, 2, boot2))
        val completed = b.stop(201_000, boot2 + 201_000)

        assertTrue(completed.quality.longestGapMs >= 1_800_000, "the 30 min gap is real time, not 3 min")
        assertLiveEqualsReplay(completed, "a1")
    }

    @Test
    fun aRecoveryInTheSameBootLeavesTheClockAlone() {
        val a = ActivityRecorder(store)
        a.start("a1", Sport.RUN, 0, wallBase)
        a.gpsReady(0, wallBase)
        a.onSample(fix(0, 0))
        a.onSample(fix(10_000, 1))

        ActivityRecorder(store).recover("a1", 40_000, wallBase + 40_000)

        val recovered = events("a1").single { it.reason == Reason.RECOVERED }
        assertEquals(0L, recovered.clockOffsetMs)
    }

    @Test
    fun theRecoveryGapIsMeasuredFromTheLastStoredSample() {
        val a = ActivityRecorder(store)
        a.start("a1", Sport.RUN, 0, wallBase)
        a.gpsReady(0, wallBase)
        a.onSample(fix(0, 0))
        a.onSample(fix(10_000, 1))

        val b = ActivityRecorder(store)
        b.recover("a1", 17_000, wallBase + 17_000)
        b.onSample(fix(18_000, 2))
        val diagnostics = b.stop(18_000, wallBase + 18_000).diagnostics

        val gap = assertNotNull(diagnostics.recoveryGaps.singleOrNull())
        assertEquals(wallBase + 10_000, gap.lastSampleBeforeMs)
        assertEquals(wallBase + 18_000, gap.firstSampleAfterMs)
        assertEquals(8_000L, gap.gapMs)
        assertEquals(false, gap.rebootDetected)
    }
}
