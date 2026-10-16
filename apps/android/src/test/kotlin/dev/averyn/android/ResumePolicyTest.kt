package dev.averyn.android

import dev.averyn.domain.ActivityState
import dev.averyn.domain.LocationSample
import dev.averyn.domain.Sport
import dev.averyn.tracking.ActivityRecorder
import dev.averyn.tracking.ActivityStore
import dev.averyn.tracking.replay
import kotlinx.io.files.Path
import java.nio.file.Files
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
        dir = Files.createTempDirectory("averyn-resume-test")
        store = ActivityStore(Path(dir.toString()))
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun interruptedActivity(
        id: String,
        startMs: Long,
    ) {
        val recorder = ActivityRecorder(store)
        recorder.start(id, Sport.RUN, startMs, 1_000_000 + startMs)
        recorder.gpsReady(startMs, 1_000_000 + startMs)
        recorder.onSample(LocationSample(1_000_000 + startMs, startMs, 45.0, 9.0, 5.0))
    }

    @Test
    fun aShortInterruptionWithPreciseLocationIsResumedWithoutAsking() {
        assertEquals(ResumeAction.RESUME_NOW, resumeAction(gapMs = 60_000, hasPrecisePermission = true))
        assertEquals(ResumeAction.RESUME_NOW, resumeAction(gapMs = AUTO_RESUME_MAX_GAP_MS, hasPrecisePermission = true))
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
    fun theNewestInterruptedActivityIsTheOneToResume() {
        interruptedActivity("old", startMs = 0)
        interruptedActivity("new", startMs = 500_000)

        val latest = latestInterrupted(store, nowTimeMs = 1_000_000 + 600_000)

        assertEquals("new", latest?.metadata?.activityId)
        assertEquals(ActivityState.RECORDING, latest?.state)
        assertEquals(100_000L, latest?.gapMs)
    }

    @Test
    fun nothingInterruptedMeansNothingToResume() {
        assertNull(latestInterrupted(store, nowTimeMs = 0))
    }

    @Test
    fun olderInterruptedActivitiesAreFinishedAndTheNewestIsLeftAlone() {
        interruptedActivity("old", startMs = 0)
        interruptedActivity("new", startMs = 500_000)

        val finished =
            finishOlderInterrupted(store, keepId = "new", nowElapsedRealtimeMs = 700_000, nowTimeMs = 1_700_000)

        assertEquals(1, finished)
        assertEquals(ActivityState.COMPLETED, replay(store, "old").lastState)
        assertEquals(listOf("new"), store.interrupted().map { it.activityId })
    }
}
