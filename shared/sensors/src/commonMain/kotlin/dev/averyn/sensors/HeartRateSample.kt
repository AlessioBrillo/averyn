package dev.averyn.sensors

import kotlinx.serialization.Serializable

/**
 * One heart-rate sample, as reported by a standard BLE Heart Rate sensor (GATT `0x180D` / `0x2A37`).
 * `contact` is null when the sensor does not report contact status.
 */
@Serializable
data class HeartRateSample(
    /** Wall-clock UTC epoch millis. */
    val timeMs: Long,
    /** Monotonic millis since boot, used for ordering and durations (same clock as [dev.averyn.domain.LocationSample.elapsedRealtimeMs]). */
    val elapsedRealtimeMs: Long,
    /** Beats per minute, as reported. 0 means "no reading" from this sample, not a measured zero. */
    val bpm: Int,
    /** true = sensor in contact with skin, false = not in contact, null = not supported/reported. */
    val contact: Boolean? = null,
) {
    init {
        require(bpm in 0..300) { "bpm out of plausible range: $bpm" }
    }
}

/**
 * One raw RR interval (ms between consecutive beats), carried in `0x2A37`'s flag-gated RR field.
 * Health (special category) data per ADR-0013/0021: stored only with `rr_recording` consent.
 */
@Serializable
data class RrInterval(
    val timeMs: Long,
    val elapsedRealtimeMs: Long,
    val rrMs: Int,
) {
    init {
        require(rrMs in 1..3000) { "rrMs out of plausible range: $rrMs" }
    }
}
