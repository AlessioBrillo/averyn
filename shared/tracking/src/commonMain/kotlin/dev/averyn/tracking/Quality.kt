package dev.averyn.tracking

import dev.averyn.domain.LocationSample
import dev.averyn.metrics.haversineMeters
import kotlinx.serialization.Serializable
import kotlin.math.abs

/** Algorithm version stamped on every [QualityReport]. Spec: docs/metrics/gps-quality.md. */
const val QUALITY_ALGORITHM_VERSION = "quality-v1"

/** Per-sample quality flags (TDD-0001 §5.4). A sample may carry several. Raw data is never dropped for these. */
enum class QualityFlag {
    POOR_ACCURACY,
    DUPLICATE,
    NON_MONOTONIC_TIME,
    SPEED_OUTLIER,
    JUMP,
    ALTITUDE_SPIKE,
}

/** Legs ending in a sample carrying this flag are excluded from `distance-v1` (docs/metrics/distance.md). */
val QualityFlag.excludesFromDistance: Boolean
    get() = this == QualityFlag.DUPLICATE || this == QualityFlag.NON_MONOTONIC_TIME || this == QualityFlag.JUMP

private const val POOR_ACCURACY_THRESHOLD_M = 30.0
private const val OUTLIER_SPEED_MPS = 50.0
private const val JUMP_MIN_DISTANCE_M = 500.0
private const val ALTITUDE_SPIKE_SPEED_MPS = 10.0

/**
 * Incremental, stateful `quality-v1` assessment (docs/metrics/gps-quality.md). Call [assess] once per raw
 * sample, in arrival order; it never mutates or drops the sample (TDD-0001 F5), only reports flags.
 */
class QualityAssessor {
    private var lastAccepted: LocationSample? = null

    fun assess(sample: LocationSample): Set<QualityFlag> {
        val flags = mutableSetOf<QualityFlag>()
        if (sample.horizontalAccuracyM > POOR_ACCURACY_THRESHOLD_M) flags += QualityFlag.POOR_ACCURACY

        val previous = lastAccepted
        if (previous != null) {
            val isDuplicate =
                sample.latitude == previous.latitude &&
                    sample.longitude == previous.longitude &&
                    sample.timeMs == previous.timeMs
            if (isDuplicate) flags += QualityFlag.DUPLICATE

            val nonMonotonic = sample.elapsedRealtimeMs <= previous.elapsedRealtimeMs
            if (nonMonotonic) {
                flags += QualityFlag.NON_MONOTONIC_TIME
            } else if (!isDuplicate) {
                val elapsedS = (sample.elapsedRealtimeMs - previous.elapsedRealtimeMs) / 1000.0
                val legM = haversineMeters(previous.latitude, previous.longitude, sample.latitude, sample.longitude)
                val impliedSpeed = legM / elapsedS
                if (impliedSpeed > OUTLIER_SPEED_MPS) {
                    flags += if (legM >= JUMP_MIN_DISTANCE_M) QualityFlag.JUMP else QualityFlag.SPEED_OUTLIER
                }
                val prevAltitudeM = previous.altitudeM
                val altitudeM = sample.altitudeM
                if (prevAltitudeM != null && altitudeM != null) {
                    val verticalSpeed = abs(altitudeM - prevAltitudeM) / elapsedS
                    if (verticalSpeed > ALTITUDE_SPIKE_SPEED_MPS) flags += QualityFlag.ALTITUDE_SPIKE
                }
            }
        }

        // DUPLICATE/NON_MONOTONIC_TIME samples don't become the new comparison baseline (gps-quality.md);
        // a SPEED_OUTLIER/JUMP/ALTITUDE_SPIKE sample still does, since it's a plausible current position.
        if (QualityFlag.DUPLICATE !in flags && QualityFlag.NON_MONOTONIC_TIME !in flags) {
            lastAccepted = sample
        }
        return flags
    }
}

/** Sample-interval buckets for [QualityReport.intervalDistribution]. */
enum class IntervalBucket { UNDER_2S, FROM_2S_TO_5S, FROM_5S_TO_15S, FROM_15S_TO_60S, OVER_60S }

private fun bucketFor(intervalMs: Long): IntervalBucket =
    when {
        intervalMs < 2_000 -> IntervalBucket.UNDER_2S
        intervalMs < 5_000 -> IntervalBucket.FROM_2S_TO_5S
        intervalMs < 15_000 -> IntervalBucket.FROM_5S_TO_15S
        intervalMs < 60_000 -> IntervalBucket.FROM_15S_TO_60S
        else -> IntervalBucket.OVER_60S
    }

enum class QualityGrade { GOOD, FAIR, POOR }

@Serializable
data class QualityReport(
    val algorithmVersion: String,
    val flagCounts: Map<QualityFlag, Int>,
    val longestGapMs: Long,
    val intervalDistribution: Map<IntervalBucket, Int>,
    val accuracyWithin20mPercent: Double,
    val grade: QualityGrade,
)

/**
 * Incremental, O(1)-memory companion to [QualityAssessor]: call [add] once per raw sample with the flags
 * [QualityAssessor.assess] produced for it, in the same arrival order — never materializes the sample list,
 * so it stays bounded for a 10 h activity (TDD-0001 §4). [build] is then also O(1), safe to call live.
 */
class QualityReportAccumulator {
    private val flagCounts = QualityFlag.entries.associateWith { 0 }.toMutableMap()
    private var totalSamples = 0
    private var flaggedSampleCount = 0
    private var longestGapMs = 0L
    private val intervalDistribution = IntervalBucket.entries.associateWith { 0 }.toMutableMap()
    private var accurateDurationMs = 0L
    private var totalDurationMs = 0L
    private var lastAccepted: LocationSample? = null

    fun add(
        sample: LocationSample,
        flags: Set<QualityFlag>,
    ) {
        totalSamples++
        if (flags.isNotEmpty()) flaggedSampleCount++
        flags.forEach { flagCounts[it] = flagCounts.getValue(it) + 1 }

        // Same "accepted" rule as QualityAssessor's baseline (gps-quality.md): a DUPLICATE/NON_MONOTONIC_TIME
        // sample doesn't open a new gap/interval measurement, but is still counted in flagCounts above.
        if (QualityFlag.DUPLICATE in flags || QualityFlag.NON_MONOTONIC_TIME in flags) return
        lastAccepted?.let { previous ->
            val intervalMs = sample.elapsedRealtimeMs - previous.elapsedRealtimeMs
            longestGapMs = maxOf(longestGapMs, intervalMs)
            val bucket = bucketFor(intervalMs)
            intervalDistribution[bucket] = intervalDistribution.getValue(bucket) + 1
            totalDurationMs += intervalMs
            if (previous.horizontalAccuracyM <= 20.0) accurateDurationMs += intervalMs
        }
        lastAccepted = sample
    }

    fun build(): QualityReport {
        val accuracyWithin20mPercent = if (totalDurationMs > 0) 100.0 * accurateDurationMs / totalDurationMs else 0.0
        val flaggedRatio = if (totalSamples > 0) flaggedSampleCount.toDouble() / totalSamples else 0.0
        val grade =
            when {
                flaggedRatio > 0.20 || accuracyWithin20mPercent < 50.0 -> QualityGrade.POOR
                flaggedRatio <= 0.05 && accuracyWithin20mPercent >= 90.0 -> QualityGrade.GOOD
                else -> QualityGrade.FAIR
            }
        return QualityReport(
            algorithmVersion = QUALITY_ALGORITHM_VERSION,
            flagCounts = flagCounts.toMap(),
            longestGapMs = longestGapMs,
            intervalDistribution = intervalDistribution.toMap(),
            accuracyWithin20mPercent = accuracyWithin20mPercent,
            grade = grade,
        )
    }
}

/** Convenience for tests/fixtures: folds a whole (sample, flags) list through a fresh accumulator. */
fun buildQualityReport(flaggedSamples: List<Pair<LocationSample, Set<QualityFlag>>>): QualityReport {
    val accumulator = QualityReportAccumulator()
    flaggedSamples.forEach { (sample, flags) -> accumulator.add(sample, flags) }
    return accumulator.build()
}
