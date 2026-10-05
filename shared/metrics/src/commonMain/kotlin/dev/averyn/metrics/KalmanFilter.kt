package dev.averyn.metrics

/**
 * A 1D Kalman Filter for smoothing noisy sensor data (e.g., GPS latitude, longitude, altitude).
 *
 * The Kalman Filter is essential for reducing GPS jitter. By balancing the uncertainty in our
 * process model (movement) and the noise in the measurements (GPS signal degradation), 
 * it produces a smooth, accurate estimate of the true value.
 *
 * @param processNoise (Q) - The covariance of the process noise. Lower means we trust the 
 *        model more (smoother output, but higher lag).
 * @param measurementNoise (R) - The covariance of the measurement noise. Lower means we 
 *        trust the sensors more (less lag, but more noise).
 */
class KalmanFilter(
    private val processNoise: Double,
    private val measurementNoise: Double,
) {
    private var estimate: Double = 0.0
    private var errorCovariance: Double = 1.0
    private var initialized: Boolean = false

    /**
     * Initialize or reset the filter with a starting value.
     */
    fun reset(initialValue: Double) {
        estimate = initialValue
        errorCovariance = 1.0
        initialized = true
    }

    /**
     * Updates the filter with a new measurement and returns the smoothed estimate.
     */
    fun update(measurement: Double): Double {
        if (!initialized) {
            reset(measurement)
            return estimate
        }

        // Prediction phase (Process Model: state remains constant + noise)
        val predictedErrorCovariance = errorCovariance + processNoise

        // Measurement Update phase (Kalman Gain)
        val kalmanGain = predictedErrorCovariance / (predictedErrorCovariance + measurementNoise)
        
        // Update estimate
        estimate += kalmanGain * (measurement - estimate)
        
        // Update error covariance
        errorCovariance = (1 - kalmanGain) * predictedErrorCovariance

        return estimate
    }

    fun getEstimate(): Double = estimate
}
