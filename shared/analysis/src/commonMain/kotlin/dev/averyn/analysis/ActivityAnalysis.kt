package dev.averyn.analysis

import kotlinx.serialization.Serializable

/** Version of the split algorithm. Spec: docs/metrics/splits.md. */
const val SPLITS_ALGORITHM_VERSION = "splits-v1"

/** Version of the elevation algorithm. Spec: docs/metrics/elevation.md. */
const val ELEVATION_ALGORITHM_VERSION = "elevation-v1"

/** Version of the display series (the decimation). Spec: docs/metrics/activity-analysis.md. */
const val SERIES_ALGORITHM_VERSION = "series-v1"

/** Version of the shape of the JSON the API returns. A new shape is a new number, never an edit. */
const val ANALYSIS_SCHEMA_VERSION = 1

/** The elevation comes from the altitude the samples carry. */
const val ELEVATION_SOURCE_RECORDED = "recorded"

/** There is no elevation: the samples carry no altitude, which is not the same as a flat activity. */
const val ELEVATION_SOURCE_NONE = "none"

/** Which algorithm produced which number. Nothing is mixed silently (report §4.3). */
@Serializable
data class AnalysisAlgorithmVersions(
    val splits: String,
    val elevation: String,
    val series: String,
    val heartRate: String,
)

/** The activity's own totals, the same ones the summary shows: elapsed = paused + stationary + moving. */
@Serializable
data class AnalysisTotals(
    val distanceM: Double,
    val elapsedMs: Long,
    val movingMs: Long,
    val pausedMs: Long,
    val stationaryMs: Long,
)

/**
 * The elevation of the activity. `source` is `recorded` when the samples carry an altitude, `none` when they
 * do not: an activity with no altitude has no elevation, not a flat one.
 */
@Serializable
data class AnalysisElevation(
    val source: String,
    val gainM: Double,
    val lossM: Double,
    val minM: Double,
    val maxM: Double,
    val avgM: Double,
    /** Share of the samples that carried an altitude; below 100 % the numbers are partial. */
    val coveragePercent: Double,
)

/** The heart rate of the activity, `hr-v2` (docs/metrics/heart-rate.md). Null when there was no HR. */
@Serializable
data class AnalysisHeartRate(
    val minBpm: Int,
    val avgBpm: Int,
    val maxBpm: Int,
    /** Share of the recording time covered by a usable HR sample. */
    val coveragePercent: Double,
    /** Expected 1 s intervals of the recording time without a usable sample. */
    val dropouts: Int,
    /** Samples outside the plausible range: counted, never used. */
    val artifacts: Int,
)

/** One automatic split: a stretch of the activity of about [AnalysisSplitLength]. */
@Serializable
data class AnalysisSplit(
    /** 1-based position of the split. */
    val index: Int,
    val startDistanceM: Double,
    val endDistanceM: Double,
    /** Wall time of the split, a pause inside it included. */
    val elapsedMs: Long,
    /** The time of the split the user was actually moving: the pace is computed on this. */
    val movingMs: Long,
    val avgSpeedMps: Double,
    /** null when the activity has no altitude. */
    val elevationGainM: Double?,
    val elevationLossM: Double?,
    /** null when the split has no heart-rate sample. */
    val avgHrBpm: Int?,
    /** true for the last split when the activity ended before the next boundary. */
    val partial: Boolean,
)

/** The heart-rate samples over time, decimated for the chart. */
@Serializable
data class AnalysisHeartRateSeries(
    val elapsedMs: List<Long>,
    val bpm: List<Int>,
    /** The stretch of samples each point belongs to: a change of number is a gap of the sensor, not a pause. */
    val segment: List<Int>,
)

/**
 * The display series of the activity: a bounded, decimated view of the track, the elevation and the heart rate.
 * It is a drawing, not a measurement: the split and the totals are the numbers to trust.
 */
@Serializable
data class AnalysisSeries(
    /** Cumulative distance at each point, in metres. */
    val distanceM: List<Double>,
    /** Smoothed altitude at each point, null where the activity has none. */
    val elevationM: List<Double?>,
    /** Milliseconds since the first sample of the activity. */
    val elapsedMs: List<Long>,
    /** The recording interval each point belongs to: a change of number is a pause, and the line breaks there. */
    val segment: List<Int>,
    val heartRate: AnalysisHeartRateSeries,
)

/** Everything the analysis of one activity holds. */
@Serializable
data class ActivityAnalysis(
    val schemaVersion: Int,
    val algorithmVersions: AnalysisAlgorithmVersions,
    /** The length of a split: 1 km, 5 km for a ride. */
    val splitLengthM: Double,
    val totals: AnalysisTotals,
    val elevation: AnalysisElevation?,
    val heartRate: AnalysisHeartRate?,
    val splits: List<AnalysisSplit>,
    val series: AnalysisSeries,
)
