package dev.averyn.sensors

import kotlin.math.roundToInt

/** Algorithm version for heart-rate metrics. Spec: docs/metrics/heart-rate.md. */
const val HR_ALGORITHM_VERSION = "hr-v2"

private const val EXPECTED_INTERVAL_MS = 1000L
private const val BPM_MIN = 1
private const val BPM_MAX = 240

/**
 * Result of `hr-v2` over the heart-rate samples of one activity (docs/metrics/heart-rate.md).
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

    /**
     * The metrics of the activity.
     *
     * [recordingMs] is the time the activity was recording, **the pauses excluded** (`hr-v2`): the samples of
     * a paused activity are not recorded either, so counting the pause in the denominator would report a strap
     * that worked perfectly as a half-covered one.
     */
    fun snapshot(recordingMs: Long): HeartRateSnapshot {
        val expected = expectedIntervals(recordingMs)
        if (values.isEmpty()) {
            return HeartRateSnapshot(0, 0, 0, 0.0, expected.toInt(), artifactCount)
        }
        val coverage = 100.0 * values.size / expected.coerceAtLeast(1)
        return HeartRateSnapshot(
            avgHrBpm = values.average().roundToInt(),
            maxHrBpm = values.maxOrNull() ?: 0,
            minHrBpm = values.minOrNull() ?: 0,
            coveragePercent = coverage.coerceIn(0.0, 100.0),
            dropouts = (expected - values.size).coerceAtLeast(0).toInt(),
            artifacts = artifactCount,
        )
    }

    private fun expectedIntervals(recordingMs: Long): Long =
        (recordingMs + EXPECTED_INTERVAL_MS - 1) / EXPECTED_INTERVAL_MS
}
