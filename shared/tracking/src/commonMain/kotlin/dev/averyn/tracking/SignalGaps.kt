package dev.averyn.tracking

import dev.averyn.sensors.HeartRateSnapshot
import kotlinx.serialization.Serializable

/** A stretch longer than the threshold with no sample of a signal, inside one continuous RECORDING interval. */
@Serializable
data class SignalGap(
    /** Wall-clock time of the last sample before the gap. */
    val fromMs: Long,
    val durationMs: Long,
)

/** The gaps of one signal (GPS fixes or heart-rate readings). `count` is complete; `gaps` keeps the first [MAX_LISTED_GAPS]. */
@Serializable
data class SignalGaps(
    val thresholdMs: Long,
    val count: Int,
    val longestMs: Long,
    val gaps: List<SignalGap>,
)

/** The heart-rate part of `diagnostics-v5`: what S15 compares with the reference strap. */
@Serializable
data class HeartRateDiagnostics(
    val avgBpm: Int,
    val maxBpm: Int,
    val coveragePercent: Double,
    val artifacts: Int,
    val gaps: SignalGaps,
)

/** The strap's diagnostics of a replayed file (the recording time is the elapsed time minus the pauses, as `hr-v2`). */
internal fun ReplayResult.heartRateDiagnosticsOf(): HeartRateDiagnostics? {
    if (!hasHeartRate) return null
    val m = metrics.snapshot()
    return heartRateDiagnostics(
        heartRate.snapshot((m.elapsedMs - m.pausedMs).coerceAtLeast(0L)),
        heartRateGaps.snapshot(),
    )
}

internal fun heartRateDiagnostics(
    snapshot: HeartRateSnapshot,
    gaps: SignalGaps,
) = HeartRateDiagnostics(snapshot.avgHrBpm, snapshot.maxHrBpm, snapshot.coveragePercent, snapshot.artifacts, gaps)

const val GAP_THRESHOLD_MS = 10_000L
const val MAX_LISTED_GAPS = 50

/**
 * Finds the gaps of a signal between consecutive samples of the RECORDING state, on the continuous clock.
 *
 * A pause, an interruption or a recovery ends the stream ([breakStream]): a gap is silence while the activity was
 * recording, not the time it was not. The live recorder and [replay] feed it the same way, so the report of a
 * recording and the one rebuilt from its file agree.
 */
class SignalGapTracker(
    private val thresholdMs: Long = GAP_THRESHOLD_MS,
) {
    private var lastElapsedMs: Long? = null
    private var lastTimeMs = 0L
    private var count = 0
    private var longestMs = 0L
    private val listed = mutableListOf<SignalGap>()

    fun onSample(
        elapsedMs: Long,
        timeMs: Long,
    ) {
        val previous = lastElapsedMs
        if (previous != null && elapsedMs - previous > thresholdMs) {
            val duration = elapsedMs - previous
            count++
            if (duration > longestMs) longestMs = duration
            if (listed.size < MAX_LISTED_GAPS) listed += SignalGap(lastTimeMs, duration)
        }
        lastElapsedMs = elapsedMs
        lastTimeMs = timeMs
    }

    fun breakStream() {
        lastElapsedMs = null
    }

    fun snapshot() = SignalGaps(thresholdMs, count, longestMs, listed.toList())
}
