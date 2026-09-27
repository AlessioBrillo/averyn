package dev.averyn.tracking

import dev.averyn.domain.LocationSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QualityAssessorTest {
    private fun sample(
        elapsedMs: Long,
        lat: Double,
        lon: Double,
        accuracyM: Double = 5.0,
        timeMs: Long = elapsedMs,
        altitudeM: Double? = null,
    ) = LocationSample(timeMs, elapsedMs, lat, lon, accuracyM, altitudeM)

    @Test
    fun firstSampleOnlyChecksAccuracy() {
        val assessor = QualityAssessor()
        assertEquals(setOf(QualityFlag.POOR_ACCURACY), assessor.assess(sample(0, 0.0, 0.0, accuracyM = 40.0)))
    }

    @Test
    fun goodAccuracyIsUnflagged() {
        val assessor = QualityAssessor()
        assertTrue(assessor.assess(sample(0, 0.0, 0.0, accuracyM = 5.0)).isEmpty())
    }

    @Test
    fun exactDuplicateIsFlagged() {
        val assessor = QualityAssessor()
        assessor.assess(sample(0, 45.0, 9.0, timeMs = 1_000))
        val flags = assessor.assess(sample(1_000, 45.0, 9.0, timeMs = 1_000))
        assertEquals(setOf(QualityFlag.DUPLICATE), flags)
    }

    @Test
    fun nonIncreasingElapsedTimeIsFlagged() {
        val assessor = QualityAssessor()
        assessor.assess(sample(5_000, 45.0, 9.0))
        val flags = assessor.assess(sample(4_000, 45.1, 9.1))
        assertEquals(setOf(QualityFlag.NON_MONOTONIC_TIME), flags)
    }

    @Test
    fun shortFastLegIsSpeedOutlierNotJump() {
        val assessor = QualityAssessor()
        assessor.assess(sample(0, 0.0, 0.0))
        // ~100 m in 1 s = 100 m/s: over the 50 m/s threshold, but under the 500 m jump floor.
        val flags = assessor.assess(sample(1_000, 100.0 / 111_195.08, 0.0))
        assertEquals(setOf(QualityFlag.SPEED_OUTLIER), flags)
    }

    @Test
    fun longFastLegIsJump() {
        val assessor = QualityAssessor()
        assessor.assess(sample(0, 0.0, 0.0))
        // ~1000 m in 1 s: over both the speed and the jump-distance threshold.
        val flags = assessor.assess(sample(1_000, 1000.0 / 111_195.08, 0.0))
        assertEquals(setOf(QualityFlag.JUMP), flags)
    }

    @Test
    fun steepAltitudeChangeIsFlagged() {
        val assessor = QualityAssessor()
        assessor.assess(sample(0, 0.0, 0.0, altitudeM = 0.0))
        val flags = assessor.assess(sample(1_000, 0.0, 0.0, altitudeM = 50.0)) // 50 m/s vertical
        assertEquals(setOf(QualityFlag.ALTITUDE_SPIKE), flags)
    }

    @Test
    fun duplicateAndNonMonotonicDontBecomeTheNewBaseline() {
        val assessor = QualityAssessor()
        assessor.assess(sample(10_000, 45.0, 9.0, timeMs = 10_000))
        assessor.assess(sample(10_000, 45.0, 9.0, timeMs = 10_000)) // non-monotonic (elapsed didn't increase)
        // Compared against the *original* baseline (10_000 ms), not the rejected one: a normal next fix
        // 1 s later must not spuriously look like a huge implied-speed jump because the baseline stuck.
        val flags = assessor.assess(sample(11_000, 45.0001, 9.0))
        assertTrue(QualityFlag.JUMP !in flags && QualityFlag.SPEED_OUTLIER !in flags)
    }
}
