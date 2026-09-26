package dev.averyn.domain

import dev.averyn.domain.ActivityState.COMPLETED
import dev.averyn.domain.ActivityState.FAILED
import dev.averyn.domain.ActivityState.IDLE
import dev.averyn.domain.ActivityState.PAUSED
import dev.averyn.domain.ActivityState.PREPARING
import dev.averyn.domain.ActivityState.RECORDING
import dev.averyn.domain.ActivityState.STOPPING
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActivityStateTest {
    // Mirrors the diagram in docs/design/TDD-0001-tracking-engine.md — update both together.
    private val legal =
        setOf(
            IDLE to PREPARING,
            PREPARING to RECORDING,
            PREPARING to IDLE,
            PREPARING to FAILED,
            RECORDING to PAUSED,
            PAUSED to RECORDING,
            RECORDING to STOPPING,
            PAUSED to STOPPING,
            RECORDING to FAILED,
            PAUSED to FAILED,
            STOPPING to COMPLETED,
            STOPPING to FAILED,
        )

    @Test
    fun exactlyTheDocumentedTransitionsAreLegal() {
        for (from in ActivityState.entries) {
            for (to in ActivityState.entries) {
                assertEquals((from to to) in legal, from.canTransitionTo(to), "$from -> $to")
            }
        }
    }

    @Test
    fun terminalStatesHaveNoExit() {
        assertTrue(COMPLETED.isTerminal && FAILED.isTerminal)
        assertFalse(RECORDING.isTerminal)
    }

    @Test
    fun illegalTransitionThrows() {
        assertFailsWith<IllegalStateException> { IDLE.transitionTo(RECORDING) }
        assertEquals(RECORDING, PREPARING.transitionTo(RECORDING))
    }

    @Test
    fun sampleRejectsInvalidCoordinates() {
        assertFailsWith<IllegalArgumentException> { LocationSample(0, 0, 91.0, 0.0, 5.0) }
        assertFailsWith<IllegalArgumentException> { LocationSample(0, 0, 0.0, 181.0, 5.0) }
        assertFailsWith<IllegalArgumentException> { LocationSample(0, 0, 0.0, 0.0, -1.0) }
    }
}
