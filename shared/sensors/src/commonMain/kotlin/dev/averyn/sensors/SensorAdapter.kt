package dev.averyn.sensors

/**
 * One BLE heart-rate sensor, owned by the native app layer (Android `BluetoothGatt`, iOS `CBPeripheral`).
 * `shared/` never touches platform BLE: it receives already-parsed [HeartRateSample]/[RrInterval] values
 * and persists them (ADR-0004, ADR-0014).
 *
 * The adapter is created per activity session; [consentRequired] tells the caller whether a consent
 * scope must be granted before [start] may begin streaming (ADR-0014: consent gates the adapter, not only the API).
 */
interface SensorAdapter {
    /** True when the platform can reach a heart-rate sensor right now (radio on, permission granted). */
    val isAvailable: Boolean

    /** Whether this adapter needs a consent scope before recording (true for RR, false for plain bpm). */
    val consentRequired: Boolean

    /**
     * Starts streaming for [sessionId]. Samples are delivered through [onSample]/[onRrInterval],
     * which the implementer wires to the store. Throws [IllegalStateException] when [consentRequired]
     * and no consent is known (fail-closed, ADR-0021).
     */
    suspend fun start(sessionId: String)

    /** Stops streaming and releases the connection. Safe to call when not started. */
    suspend fun stop()

    /** Callbacks set by the caller before [start]. */
    var onSample: ((HeartRateSample) -> Unit)?
    var onRrInterval: ((RrInterval) -> Unit)?
}
