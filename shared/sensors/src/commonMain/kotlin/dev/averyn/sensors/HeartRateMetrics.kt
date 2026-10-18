package dev.averyn.sensors

/** Algorithm version for heart-rate metrics. Spec: docs/metrics/heart-rate.md. */
const val HR_ALGORITHM_VERSION = "hr-v1"

private const val EXPECTED_INTERVAL_MS = 1000L
private const val BPM_MIN = 1
private const val BPM_MAX = 240

/**
 * Result of `hr-v1` over the heart-rate samples of one activity (docs/metrics/heart-rate.md).
 * No interpolation, no smoothing, no zones: zones need a user profile (deferred increment).
 */
data class HeartRateSnapshot(
    val avgHrBpm: Int,
    val maxHrBpm: Int,
    val minHrBpm: Int,
    /** Share of expected 1 s intervals that had a usable HR sample. */
    val coveragePercent: Double,
    /** Expected intervals without a usable HR sample. */
    val dropouts: Int,
    /** Samples outside [BPM_MIN, BPM_MAX], counted but excluded from avg/max/min. */
    val artifacts: Int,
)

/**
 * Incremental accumulator over `HR ` records recorded while the activity is `RECORDING`.
 * The caller feeds samples in arrival order and tells when the activity pauses/resumes
 * via [onPause]/[onResume]; samples received while paused are excluded from the metric.
 */
class HeartRateMetrics {
    private var values = mutableListOf<Int>()
    private var artifactCount = 0

    /** Call for every HR sample recorded while the activity is in RECORDING state. */
    fun onSample(bpm: Int) {
        if (bpm in BPM_MIN..BPM_MAX) values += bpm else artifactCount++
    }

    fun snapshot(durationMs: Long): HeartRateSnapshot {
        val expected = expectedIntervals(durationMs)
        if (values.isEmpty()) {
            return HeartRateSnapshot(0, 0, 0, 0.0, expected.toInt(), artifactCount.toInt())
        }
        val coverage = 100.0 * values.size / expected.coerceAtLeast(1)
        return HeartRateSnapshot(
            avgHrBpm = Math.round(values.average()).toInt(),
            maxHrBpm = values.maxOrNull() ?: 0,
            minHrBpm = values.minOrNull() ?: 0,
            coveragePercent = coverage.coerceIn(0.0, 100.0),
            dropouts = (expected - values.size).coerceAtLeast(0).toInt(),
            artifacts = artifactCount,
        )
    }

    private fun expectedIntervals(durationMs: Long): Long =
        (durationMs + EXPECTED_INTERVAL_MS - 1) / EXPECTED_INTERVAL_MS
}
