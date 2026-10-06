package dev.averyn.metrics

/**
 * Filters altitude data to calculate realistic elevation gain/loss.
 * Uses a simple dead-band filter to ignore micro-oscillations.
 */
class ElevationFilter(
    private val thresholdMeters: Double = 2.0,
) {
    private var lastAltitude: Double? = null
    var gainMeters = 0.0
    var lossMeters = 0.0

    fun onAltitudeSample(altitude: Double) {
        val last = lastAltitude
        if (last != null) {
            val delta = altitude - last
            if (delta > thresholdMeters) {
                gainMeters += delta
            } else if (delta < -thresholdMeters) {
                lossMeters += delta.absoluteValue
            }
        }
        lastAltitude = altitude
    }
}

private val Double.absoluteValue: Double get() = if (this < 0) -this else this
