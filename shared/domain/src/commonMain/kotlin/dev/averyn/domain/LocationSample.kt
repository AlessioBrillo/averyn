package dev.averyn.domain

import kotlinx.serialization.Serializable

/**
 * One raw location fix, as reported by the platform. See TDD-0001 §5.3.
 *
 * Raw samples are immutable and never modified by filtering; quality problems are flagged elsewhere.
 */
@Serializable
data class LocationSample(
    /** Wall-clock UTC epoch millis. Can jump (NTP, user change): do not use alone for ordering. */
    val timeMs: Long,
    /** Monotonic millis since boot. Used for ordering and durations. */
    val elapsedRealtimeMs: Long,
    val latitude: Double,
    val longitude: Double,
    /**
     * Platform-reported horizontal accuracy radius in meters; null when the source has none.
     *
     * A recorded fix always has one. An imported file may not: a GPX track carries no accuracy, and inventing
     * a number (a sentinel like 9999) would later be read as a real measurement. Null means "not measurable",
     * and the quality grade of such an activity is [dev.averyn.tracking.QualityGrade.UNKNOWN].
     */
    val horizontalAccuracyM: Double?,
    val altitudeM: Double? = null,
    val speedMps: Double? = null,
    val bearingDeg: Double? = null,
) {
    init {
        require(latitude in -90.0..90.0) { "latitude out of range: $latitude" }
        require(longitude in -180.0..180.0) { "longitude out of range: $longitude" }
        require(horizontalAccuracyM == null || horizontalAccuracyM >= 0.0) {
            "horizontalAccuracyM must be null or >= 0: $horizontalAccuracyM"
        }
    }
}
