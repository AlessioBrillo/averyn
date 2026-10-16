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
import kotlin.test.assertNull

class ResumePolicyTest {
    private lateinit var dir: java.nio.file.Path
    private lateinit var store: ActivityStore

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("averyn-resume-test")
        store = ActivityStore(Path(dir.toString()))
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private val wallBase = 1_000_000L

    private fun interruptedActivity(
        id: String,
        startMs: Long,
    ) {
        val recorder = ActivityRecorder(store)
        recorder.start(id, Sport.RUN, startMs, wallBase + startMs)
        recorder.gpsReady(startMs, wallBase + startMs)
        recorder.onSample(LocationSample(wallBase + startMs, startMs, 45.0, 9.0, 5.0))
    }

    @Test
    fun aShortInterruptionWithPreciseLocationIsResumedWithoutAsking() {
        assertEquals(ResumeAction.RESUME_NOW, resumeAction(gapMs = 60_000, hasPrecisePermission = true))
        assertEquals(ResumeAction.RESUME_NOW, resumeAction(AUTO_RESUME_MAX_GAP_MS, hasPrecisePermission = true))
    }

    @Test
    fun aLongInterruptionOrMissingPermissionAsksTheUser() {
        assertEquals(ResumeAction.ASK_USER, resumeAction(AUTO_RESUME_MAX_GAP_MS + 1, hasPrecisePermission = true))
        assertEquals(ResumeAction.ASK_USER, resumeAction(gapMs = 1_000, hasPrecisePermission = false))
    }

    @Test
    fun aClockMovedBackDoesNotBlockTheResume() {
        assertEquals(ResumeAction.RESUME_NOW, resumeAction(gapMs = -5_000, hasPrecisePermission = true))
    }

    @Test
    fun theNewestInterruptedActivityIsTheOneDecidedOn() {
        interruptedActivity("old", startMs = 0)
        interruptedActivity("new", startMs = 500_000)

        val decision = store.resumeDecision(nowTimeMs = wallBase + 600_000, hasPrecisePermission = true)

        assertEquals("new", decision?.metadata?.activityId)
        assertEquals(ActivityState.RECORDING, decision?.state)
        assertEquals(100_000L, decision?.gapMs)
        assertEquals(ResumeAction.RESUME_NOW, decision?.action)
    }

    @Test
    fun anOldInterruptionAsksEvenWithPermission() {
        interruptedActivity("a1", startMs = 0)

        val now = wallBase + AUTO_RESUME_MAX_GAP_MS + 1
        val decision = store.resumeDecision(nowTimeMs = now, hasPrecisePermission = true)

        assertEquals(ResumeAction.ASK_USER, decision?.action)
    }

    @Test
    fun aKillWhileStoppingIsAlwaysFinalizedWithoutAsking() {
        interruptedActivity("a1", startMs = 0)
        store.appendEvent("a1", StateEvent(10_000, wallBase + 10_000, ActivityState.STOPPING, Reason.USER))

        val now = wallBase + 10 * AUTO_RESUME_MAX_GAP_MS
        val decision = store.resumeDecision(nowTimeMs = now, hasPrecisePermission = false)

        assertEquals(ResumeAction.RESUME_NOW, decision?.action)
        assertEquals(ActivityState.STOPPING, decision?.state)
    }

    @Test
    fun nothingInterruptedMeansNothingToDecide() {
        assertNull(store.resumeDecision(nowTimeMs = 0, hasPrecisePermission = true))
    }

    @Test
    fun olderInterruptedActivitiesAreFinishedAndTheNewestIsLeftAlone() {
        interruptedActivity("old", startMs = 0)
        interruptedActivity("new", startMs = 500_000)

        val finished =
            store.finishOlderInterrupted("new", nowElapsedRealtimeMs = 700_000, nowTimeMs = wallBase + 700_000)

        assertEquals(1, finished)
        assertEquals(ActivityState.COMPLETED, replay(store, "old").lastState)
        assertEquals(listOf("new"), store.interrupted().map { it.activityId })
    }
}
