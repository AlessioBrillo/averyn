package dev.averyn.metrics

import dev.averyn.domain.LocationSample

/** Algorithm version for elapsed/paused/moving/stationary time. Spec: docs/metrics/moving-time.md. */
const val TIME_ALGORITHM_VERSION = "time-v1"

/** Algorithm version for current/average speed and pace. Spec: docs/metrics/speed-and-pace.md. */
const val SPEED_ALGORITHM_VERSION = "speed-v1"

private const val STATIONARY_SPEED_MPS = 0.5

data class ActivitySnapshot(
    val distanceM: Double,
    val elapsedMs: Long,
    val pausedMs: Long,
    val movingMs: Long,
    val stationaryMs: Long,
    val currentSpeedMps: Double,
    val averageSpeedMps: Double,
    /** null when average speed is 0 (nothing moved yet) — an "infinite pace" is not a displayable value. */
    val paceSecPerKm: Double?,
)

/**
 * Incremental, O(1)-memory accumulator over *accepted* samples (caller excludes DUPLICATE/NON_MONOTONIC_TIME/
 * JUMP-flagged ones, docs/metrics/distance.md) and pause/resume events, fed in arrival order. A batch summary
 * for a completed activity is just folding the same accumulator start to finish (determinism, TDD-0001 §4).
 */
class ActivityMetrics {
    private var firstElapsedRealtimeMs: Long? = null
    private var lastElapsedRealtimeMs = 0L
    private var lastSample: LocationSample? = null
    private var distanceM = 0.0
    private var pausedMs = 0L
    private var stationaryMs = 0L
    private var lastLegSpeedMps = 0.0
    private var openPauseStartMs: Long? = null

    fun onSample(sample: LocationSample) {
        if (firstElapsedRealtimeMs == null) firstElapsedRealtimeMs = sample.elapsedRealtimeMs
        lastSample?.let { previous ->
            val legM = haversineMeters(previous.latitude, previous.longitude, sample.latitude, sample.longitude)
            val legMs = sample.elapsedRealtimeMs - previous.elapsedRealtimeMs
            distanceM += legM
            lastLegSpeedMps = if (legMs > 0) legM / (legMs / 1000.0) else 0.0
            if (lastLegSpeedMps < STATIONARY_SPEED_MPS) stationaryMs += legMs
        }
        lastSample = sample
        lastElapsedRealtimeMs = sample.elapsedRealtimeMs
    }

    fun onPause(elapsedRealtimeMs: Long) {
        openPauseStartMs = elapsedRealtimeMs
        lastElapsedRealtimeMs = elapsedRealtimeMs
        // Break the leg chain: the gap between the last pre-pause sample and the first post-pause one
        // must not count as a moved/stationary leg (its "time" is already about to be counted as paused).
        lastSample = null
    }

    fun onResume(elapsedRealtimeMs: Long) {
        openPauseStartMs?.let { pausedMs += elapsedRealtimeMs - it }
        openPauseStartMs = null
        lastElapsedRealtimeMs = elapsedRealtimeMs
    }

    /** Call once when the activity stops, to close a pause interval still open at that point. */
    fun finish(elapsedRealtimeMs: Long) {
        openPauseStartMs?.let { pausedMs += elapsedRealtimeMs - it }
        openPauseStartMs = null
        lastElapsedRealtimeMs = elapsedRealtimeMs
    }

    fun snapshot(): ActivitySnapshot {
        val first = firstElapsedRealtimeMs
        val elapsedMs = if (first == null) 0L else lastElapsedRealtimeMs - first
        val movingMs = (elapsedMs - pausedMs - stationaryMs).coerceAtLeast(0L)
        val averageSpeedMps = if (movingMs > 0) distanceM / (movingMs / 1000.0) else 0.0
        val paceSecPerKm = if (averageSpeedMps > 0) 1000.0 / averageSpeedMps else null
        return ActivitySnapshot(
            distanceM = distanceM,
            elapsedMs = elapsedMs,
            pausedMs = pausedMs,
            movingMs = movingMs,
            stationaryMs = stationaryMs,
            currentSpeedMps = lastLegSpeedMps,
            averageSpeedMps = averageSpeedMps,
            paceSecPerKm = paceSecPerKm,
        )
    }
}
