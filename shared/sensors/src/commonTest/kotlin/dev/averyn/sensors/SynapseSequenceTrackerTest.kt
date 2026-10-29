package dev.averyn.sensors

import kotlin.test.Test
import kotlin.test.assertEquals

/** The count of frames the link lost, from the 16-bit counter of the feature stream (ADR-0026). */
class SynapseSequenceTrackerTest {
    @Test
    fun framesInOrderLoseNothing() {
        val tracker = SynapseSequenceTracker()

        assertEquals(0, tracker.onFrame(10))
        assertEquals(0, tracker.onFrame(11))
        assertEquals(0, tracker.onFrame(12))
        assertEquals(0, tracker.lostFrames)
    }

    @Test
    fun aGapIsCountedWithItsSize() {
        val tracker = SynapseSequenceTracker()
        tracker.onFrame(10)

        assertEquals(3, tracker.onFrame(14)) // 11, 12 and 13 never arrived
        assertEquals(3, tracker.lostFrames)
        assertEquals(1, tracker.onFrame(16))
        assertEquals(4, tracker.lostFrames)
    }

    @Test
    fun theCounterWrapsWithoutAPhantomGap() {
        val tracker = SynapseSequenceTracker()
        tracker.onFrame(65534)

        assertEquals(0, tracker.onFrame(65535))
        assertEquals(0, tracker.onFrame(0))
        assertEquals(1, tracker.onFrame(2)) // 1 was lost across the wrap
        assertEquals(1, tracker.lostFrames)
    }

    @Test
    fun aRepeatOrAnOldFrameIsCountedApartAndDoesNotMoveTheCounterBack() {
        val tracker = SynapseSequenceTracker()
        tracker.onFrame(100)
        tracker.onFrame(101)

        assertEquals(0, tracker.onFrame(101)) // the same frame again
        assertEquals(0, tracker.onFrame(99)) // an old one
        assertEquals(2, tracker.outOfOrderFrames)
        assertEquals(0, tracker.lostFrames)
        // The next real frame is judged against 101, not against the old one.
        assertEquals(0, tracker.onFrame(102))
        assertEquals(0, tracker.lostFrames)
    }

    @Test
    fun aRestartOfTheCounterIsNotAHugeGapNorAStuckCounter() {
        val tracker = SynapseSequenceTracker()
        tracker.onFrame(5000)

        assertEquals(0, tracker.onFrame(0)) // the band rebooted
        assertEquals(0, tracker.onFrame(1)) // the new run is followed, not rejected until it passes 5000
        assertEquals(1, tracker.onFrame(3))
        assertEquals(1, tracker.restarts)
        assertEquals(0, tracker.outOfOrderFrames)
        assertEquals(1, tracker.lostFrames)
    }

    @Test
    fun theFirstFrameHasNothingBeforeIt() {
        val tracker = SynapseSequenceTracker()

        assertEquals(0, tracker.onFrame(40_000))
        assertEquals(0, tracker.lostFrames)
    }
}
