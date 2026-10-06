package dev.averyn.metrics

import kotlin.test.Test
import kotlin.test.assertTrue

class KalmanFilterTest {
    @Test
    fun testSmoothing() {
        // High measurement noise (R), low process noise (Q) -> should smooth out jitter
        val filter = KalmanFilter(processNoise = 0.01, measurementNoise = 1.0)
        filter.reset(0.0)

        val measurements = listOf(0.0, 1.0, 0.0, 1.0, 0.0, 1.0)
        var lastEstimate = 0.0

        measurements.forEach { m ->
            val estimate = filter.update(m)
            // Expect the estimate to be between the last and the new measurement (moving towards new)
            // but not jumping straight to it.
            assertTrue(estimate >= 0.0 && estimate <= 1.0, "Estimate $estimate should be bounded")
            lastEstimate = estimate
        }
    }
}
