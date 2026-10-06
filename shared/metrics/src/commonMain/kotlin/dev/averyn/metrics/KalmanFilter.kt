package dev.averyn.metrics

/**
 * A 1D Kalman Filter for smoothing noisy sensor data.
 */
class KalmanFilter(
    private val processNoise: Double,
    private val measurementNoise: Double,
) {
    private var estimate: Double = 0.0
    private var errorCovariance: Double = 1.0
    private var initialized: Boolean = false

    fun reset(initialValue: Double) {
        estimate = initialValue
        errorCovariance = 1.0
        initialized = true
    }

    fun update(measurement: Double): Double {
        if (!initialized) {
            reset(measurement)
            return estimate
        }

        val predictedErrorCovariance = errorCovariance + processNoise
        val kalmanGain = predictedErrorCovariance / (predictedErrorCovariance + measurementNoise)

        estimate += kalmanGain * (measurement - estimate)
        errorCovariance = (1 - kalmanGain) * predictedErrorCovariance

        return estimate
    }

    fun getEstimate(): Double = estimate
}
