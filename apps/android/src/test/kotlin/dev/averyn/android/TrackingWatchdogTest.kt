package dev.averyn.android

import dev.averyn.domain.ActivityState
import kotlin.test.Test
import kotlin.test.assertEquals

class TrackingWatchdogTest {
    private val minute = 60_000L

    @Test
    fun warnsOnlyWhileRecordingAndOnlyAfterTheGap() {
        assertEquals(false, noFixForTooLong(ActivityState.RECORDING, 4 * minute, 1))
        assertEquals(true, noFixForTooLong(ActivityState.RECORDING, 7 * minute, 1))
        // A paused or preparing activity is not waiting for fixes it would keep.
        assertEquals(false, noFixForTooLong(ActivityState.PAUSED, 7 * minute, 1))
        assertEquals(false, noFixForTooLong(ActivityState.PREPARING, 7 * minute, 1))
    }

    @Test
    fun noClockYetIsNotAGap() {
        assertEquals(false, noFixForTooLong(ActivityState.RECORDING, 10 * minute, 0))
    }
}
