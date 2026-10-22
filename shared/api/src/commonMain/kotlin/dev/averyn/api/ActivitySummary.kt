package dev.averyn.api

import kotlinx.serialization.Serializable

/**
 * One activity in the activity list and in the detail page (TDD-0011).
 *
 * This is the contract of `GET /v1/activities` and `GET /v1/activities/{id}`: the same DTO is produced by the
 * server, consumed by the apps (through `shared:client`) and by the web app. It lives in `shared/api` so that
 * nobody has to keep a second copy of it in sync (the web app still has its own TypeScript mirror).
 */
@Serializable
data class ActivitySummary(
    val id: String,
    /** The id the activity had on the device that recorded it: the key that joins the server and the local store. */
    val clientActivityId: String,
    val sport: String,
    /** ISO-8601 instant of the first sample, in UTC (`2026-01-31T09:15:00Z`). */
    val startedAt: String,
    val finalState: String,
    val distanceM: Double,
    val elapsedMs: Long,
    val movingMs: Long,
    val pausedMs: Long,
    /** The time the activity was recording but the user was not moving (`time-v1`). */
    val stationaryMs: Long,
    val averageSpeedMps: Double,
    /** null when the average speed is 0 (speed-v1). */
    val paceSecPerKm: Double?,
    val qualityGrade: String,
    val droppedRecords: Int,
    val metricsAlgorithmVersion: String,
    val qualityAlgorithmVersion: String,
    val visibility: String,
    /** hr-v2 (docs/metrics/heart-rate.md): mean HR over RECORDING-state samples, 0 when none. */
    val avgHrBpm: Int,
    /** hr-v2: max HR over RECORDING-state samples, 0 when none. */
    val maxHrBpm: Int,
    /** Which sensor produced the HR values (`ble-hr`, `none`, …). */
    val hrSource: String,
    /** The algorithm that computed the heart rate: the numbers of two versions are never mixed silently. */
    val hrAlgorithmVersion: String,
    /** Where the activity comes from: `device` (recorded) or `gpx` (imported, TDD-0008). */
    val source: String = "device",
)

/** One page of the activity list: the items and the cursor of the next page (null on the last one). */
@Serializable
data class ActivityPage(
    val items: List<ActivitySummary>,
    /** Opaque; null on the last page. */
    val nextCursor: String?,
)
