package dev.averyn.tracking

import dev.averyn.metrics.ActivitySnapshot
import dev.averyn.sensors.HR_ALGORITHM_VERSION
import dev.averyn.sensors.HeartRateSnapshot
import kotlinx.serialization.Serializable

/** The version of the sidecar format (TDD-0011). A sidecar of another version is ignored. */
const val LOCAL_SUMMARY_SCHEMA_VERSION = 1

/**
 * The numbers of an activity, computed on the device (TDD-0011).
 *
 * It is written next to the activity file, as `<activityId>.summary.json`, when the activity ends. It is not
 * an activity of its own: it is not uploaded, it is not exported, and it holds no coordinates — only the
 * numbers the list shows when the server cannot be reached.
 *
 * The server recomputes everything from the raw file, so this file is a cache of derived data: when it is
 * missing or out of date, the numbers come from the server (or from a recomputation).
 */
@Serializable
data class LocalActivitySummary(
    val schemaVersion: Int = LOCAL_SUMMARY_SCHEMA_VERSION,
    val activityId: String,
    val sport: String,
    val startedAtMs: Long,
    val distanceM: Double,
    val elapsedMs: Long,
    val movingMs: Long,
    val pausedMs: Long,
    val stationaryMs: Long,
    val averageSpeedMps: Double,
    val paceSecPerKm: Double?,
    val qualityGrade: String,
    /** The stored lines that could not be read (F5): counted, never silent. */
    val droppedRecords: Int,
    /** The algorithms that produced the numbers, like the summary of the server. */
    val metricsAlgorithmVersion: String,
    val qualityAlgorithmVersion: String,
    /** hr-v2: the mean heart rate of the recording state, 0 when there was none. */
    val avgHrBpm: Int,
    /** hr-v2: the highest heart rate of the recording state, 0 when there was none. */
    val maxHrBpm: Int,
    /** Which sensor produced the heart rate (`ble-hr`, `none`). */
    val hrSource: String,
    val hrAlgorithmVersion: String,
    /** The wall-clock time of the last thing that happened to the activity. */
    val endedAtMs: Long,
)

/**
 * The summary of an activity, from the numbers the recorder already has.
 *
 * The same builder is used by the live path (when the activity ends) and by the recomputation (when an
 * activity of an older build has no sidecar yet): the two must never disagree.
 */
internal fun buildLocalSummary(
    activityId: String,
    sport: String,
    startedAtMs: Long,
    metrics: ActivitySnapshot,
    quality: QualityReport,
    diagnostics: DiagnosticsReport,
    heartRate: HeartRateSnapshot?,
    endedAtMs: Long,
): LocalActivitySummary =
    LocalActivitySummary(
        activityId = activityId,
        sport = sport,
        startedAtMs = startedAtMs,
        distanceM = metrics.distanceM,
        elapsedMs = metrics.elapsedMs,
        movingMs = metrics.movingMs,
        pausedMs = metrics.pausedMs,
        stationaryMs = metrics.stationaryMs,
        averageSpeedMps = metrics.averageSpeedMps,
        paceSecPerKm = metrics.paceSecPerKm,
        qualityGrade = quality.grade.name,
        droppedRecords = diagnostics.recordLosses.values.sum(),
        metricsAlgorithmVersion =
            "${diagnostics.algorithms.distance},${diagnostics.algorithms.time},${diagnostics.algorithms.speed}",
        qualityAlgorithmVersion = quality.algorithmVersion,
        avgHrBpm = heartRate?.avgHrBpm ?: 0,
        maxHrBpm = heartRate?.maxHrBpm ?: 0,
        hrSource = if (heartRate != null) HR_SOURCE_BLE else HR_SOURCE_NONE,
        hrAlgorithmVersion = HR_ALGORITHM_VERSION,
        endedAtMs = endedAtMs,
    )

/** The heart rate came from the BLE strap of the phone. */
const val HR_SOURCE_BLE = "ble-hr"

/** The activity has no heart rate at all. */
const val HR_SOURCE_NONE = "none"
