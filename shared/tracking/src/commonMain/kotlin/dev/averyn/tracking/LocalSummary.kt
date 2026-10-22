package dev.averyn.tracking

import dev.averyn.sensors.HR_ALGORITHM_VERSION

/**
 * The local summary of a stored activity, recomputed from its file (TDD-0011).
 *
 * It is the same summary the recorder writes when the activity ends, built from the same code: an activity
 * that was recorded by an older build (or whose sidecar could not be written) gets its numbers back this way.
 *
 * @return null when the file cannot be replayed: an unreadable activity has no numbers to show.
 */
fun ActivityStore.localSummaryOf(activityId: String): LocalActivitySummary? {
    val replayed = runCatching { replay(this, activityId) }.getOrNull() ?: return null
    val metrics = replayed.metrics.snapshot()
    val quality = replayed.quality.build()
    val diagnostics =
        diagnosticsReport(
            activityId = activityId,
            sport = replayed.metadata.sport,
            startedAtMs = replayed.metadata.startedAtMs,
            totalSamples = replayed.totalSamples,
            recordingSamples = replayed.recordingSamples,
            recordLosses = replayed.recordLosses,
            recoveries = replayed.recoveries,
            metrics = metrics,
            quality = quality,
            batteryStartPercent = replayed.batteryStartPercent,
            batteryEndPercent = replayed.batteryEndPercent,
            batteryReadingCount = replayed.batteryReadingCount,
            droppedInputs = emptyMap(),
            recoveryGaps = replayed.recoveryGaps.snapshot(),
            events = replayed.timeline,
            endedAtMs = replayed.lastKnownTimeMs,
            heartRate = if (replayed.hasHeartRate) HR_ALGORITHM_VERSION else null,
        )
    val recordingMs = (metrics.elapsedMs - metrics.pausedMs).coerceAtLeast(0L)
    val heartRate = if (replayed.hasHeartRate) replayed.heartRate.snapshot(recordingMs) else null
    return buildLocalSummary(
        activityId = activityId,
        sport = replayed.metadata.sport.name,
        startedAtMs = replayed.metadata.startedAtMs,
        metrics = metrics,
        quality = quality,
        diagnostics = diagnostics,
        heartRate = heartRate,
        endedAtMs = replayed.lastKnownTimeMs,
    )
}
