package dev.averyn.tracking

import dev.averyn.metrics.ActivitySnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SplitCueTrackerTest {
    private fun at(
        distanceM: Double,
        movingMs: Long,
    ) = ActivitySnapshot(
        distanceM = distanceM,
        elapsedMs = movingMs,
        pausedMs = 0,
        movingMs = movingMs,
        stationaryMs = 0,
        currentSpeedMps = 0.0,
        averageSpeedMps = 0.0,
        paceSecPerKm = null,
    )

    @Test
    fun oneCuePerKilometreWithTheTimeOfThatSplit() {
        val tracker = SplitCueTracker(1_000.0)

        assertTrue(tracker.onSnapshot(at(500.0, 150_000)).isEmpty())
        val first = tracker.onSnapshot(at(1_020.0, 306_000)).single()
        assertEquals(1, first.index)
        // The boundary is 500 of the 520 m of the last update in: 150 s + 500/520 of the next 156 s = 300 s.
        assertEquals(300_000, first.totalMovingMs)
        assertEquals(300_000, first.splitMovingMs)

        val second = tracker.onSnapshot(at(2_010.0, 600_000)).single()
        assertEquals(2, second.index)
        // 980 of the 990 m of the update: 306 s + 980/990 of 294 s = 597.03 s, a split of 297.03 s.
        assertTrue(second.totalMovingMs in 597_000..597_100)
        assertEquals(second.totalMovingMs - 300_000, second.splitMovingMs)
    }

    @Test
    fun aJumpOverSeveralBoundariesGivesOneCueForEachInOrder() {
        val tracker = SplitCueTracker(1_000.0)
        tracker.onSnapshot(at(900.0, 270_000))

        val cues = tracker.onSnapshot(at(3_100.0, 960_000))

        assertEquals(listOf(1, 2, 3), cues.map { it.index })
        assertTrue(cues.zipWithNext().all { (a, b) -> a.totalMovingMs < b.totalMovingMs })
    }

    @Test
    fun aRecoveredActivityDoesNotRepeatTheBoundariesItPassed() {
        val tracker = SplitCueTracker(1_000.0, startDistanceM = 2_400.0, startMovingMs = 720_000)

        assertTrue(tracker.onSnapshot(at(2_900.0, 870_000)).isEmpty())
        assertEquals(3, tracker.onSnapshot(at(3_050.0, 915_000)).single().index)
    }

    @Test
    fun aDistanceThatGoesBackCrossesNothingAndIsNotCountedTwice() {
        val tracker = SplitCueTracker(1_000.0)
        tracker.onSnapshot(at(1_100.0, 330_000))

        assertTrue(tracker.onSnapshot(at(950.0, 340_000)).isEmpty())
        // Back over the same boundary: it was already said.
        assertTrue(tracker.onSnapshot(at(1_050.0, 350_000)).isEmpty())
        assertEquals(2, tracker.onSnapshot(at(2_001.0, 650_000)).single().index)
    }

    @Test
    fun milesUseTheirOwnBoundary() {
        val tracker = SplitCueTracker(1_609.344)

        assertTrue(tracker.onSnapshot(at(1_600.0, 480_000)).isEmpty())
        assertEquals(1, tracker.onSnapshot(at(1_620.0, 486_000)).single().index)
    }

    @Test
    fun theUnitMustBePositive() {
        assertFailsWith<IllegalArgumentException> { SplitCueTracker(0.0) }
    }
}
