package dev.averyn.sensors

/**
 * One BLE heart-rate sensor, owned by the native app layer (Android `BluetoothGatt`, iOS `CBPeripheral`).
 * `shared/` never touches platform BLE: it receives already-parsed [HeartRateSample]/[RrInterval] values
 * and persists them (ADR-0004, ADR-0014).
 *
 * The adapter is owned by the app layer (one per process, created lazily) and started/stopped per
 * activity session via [start]/[stop]. Platform BLE must never be scanned or connected outside recording
 * (ADR-0004 §4.2).
 *
 * [supportsRr] tells the caller whether this adapter can produce RR intervals (Health class, consent-gated,
 * ADR-0021). [recordRrIntervals] is set by the caller from the cached server consent before [start] and
 * is re-read on every interval: fail-closed, default false, so no consent means no RR recording.
 */
interface SensorAdapter {
    /** True when the platform can reach a heart-rate sensor right now (radio on, permission granted). */
    val isAvailable: Boolean

    /** Whether this adapter can produce RR intervals (false when it only supports plain bpm). */
    val supportsRr: Boolean

    /**
     * Whether RR intervals may be streamed right now. Set from the cached consent before [start];
     * re-read on every interval, so a revocation mid-activity stops RR within one refresh cycle
     * without stopping bpm (ADR-0021).
     */
    var recordRrIntervals: Boolean

    /**
     * Starts streaming for [sessionId]. Samples are delivered through [onSample]/[onRrInterval],
     * which the implementer wires to the store.
     *
     * Throws [IllegalStateException] when [recordRrIntervals] is true and no consent is known
     * (fail-closed). Starting is always allowed for bpm only.
     */
    suspend fun start(sessionId: String)

    /** Stops streaming and releases the connection. Safe to call when not started. */
    suspend fun stop()

    /** Callbacks set by the caller before [start]. */
    var onSample: ((HeartRateSample) -> Unit)?
    var onRrInterval: ((RrInterval) -> Unit)?
}
