package dev.averyn.sensors

import kotlin.concurrent.Volatile

/**
 * Which consents the user has granted right now (ADR-0021, ADR-0030). The recorder reads it before every write, so a
 * value without its consent never reaches the disk: the server masks only on revocation and accepts whatever is put.
 */
data class SynapseConsents(
    val rrRecording: Boolean = false,
    val hrvAnalysis: Boolean = false,
    val signalQuality: Boolean = false,
    val stressMonitoring: Boolean = false,
    val rawSignals: Boolean = false,
) {
    companion object {
        /** Nothing granted: also what a failed read of the consents amounts to (fail closed). */
        val NONE = SynapseConsents()

        /** From the scope names the server lists (`GET /v1/consents`); a scope this build does not know is ignored. */
        fun fromScopes(scopes: Collection<String>) =
            SynapseConsents(
                rrRecording = "rr_recording" in scopes,
                hrvAnalysis = "hrv_analysis" in scopes,
                signalQuality = "signal_quality" in scopes,
                stressMonitoring = "stress_monitoring" in scopes,
                rawSignals = "raw_signals" in scopes,
            )
    }
}

/** The "not available" byte of every masked field (contract 2.2: 0xFF, 0xFFFF). */
private const val NOT_AVAILABLE: Byte = -1

/**
 * A copy of one feature-frame notification with the fields its consents do not cover set to "not available".
 * `rr_last` and `rr_count` need `rr_recording`, `rmssd` and `sdnn` need `hrv_analysis`, the stress class needs
 * `stress_monitoring`. The same rule, byte for byte, as the server's `maskFeatureSidecar`: only a version-2 frame
 * of at least 27 bytes and not the 40 of a status record; anything else is left as it came (a layout this build does
 * not know is not interpreted, ADR-0014).
 */
fun maskSynapseFeatureFrame(
    frame: ByteArray,
    consents: SynapseConsents,
): ByteArray {
    val out = frame.copyOf()
    val isFeature =
        out.size >= SYNAPSE_FEATURE_FRAME_BYTES &&
            out.size != SYNAPSE_STATUS_BYTES &&
            out[0].toInt() == SYNAPSE_FEATURE_FRAME_VERSION
    if (!isFeature) return out
    if (!consents.rrRecording) out.fill(NOT_AVAILABLE, 10, 13)
    if (!consents.hrvAnalysis) out.fill(NOT_AVAILABLE, 20, 24)
    if (!consents.stressMonitoring) out.fill(NOT_AVAILABLE, 16, 17)
    return out
}

/** Where a sidecar's records go. The store, a test or a file plugs in; the recorder knows nothing of the platform. */
fun interface SynapseSidecarSink {
    fun append(record: ByteArray)
}

/**
 * Turns what a Synapse band notifies into the two sidecars (ADR-0030), under the consents in force.
 *
 * - Feature and status notifications are written only under `signal_quality`, the scope that allows the file; the
 *   feature frames are masked first.
 * - Raw frames are written only under `raw_signals`.
 * - Every frame is written as it came, valid or not: a refused frame is data about the link, and the reader that
 *   comes later counts it.
 *
 * [consents] is replaced when the periodic re-read of the server's consents finds a change; the next frame obeys it.
 * [consents] may be replaced from another thread than the one that feeds the recorder (the periodic re-read versus the
 * BLE callback queue), so it is volatile: a revocation is seen by the next frame, not at some later flush.
 */
class SynapseRecorder(
    private val features: SynapseSidecarSink,
    private val raw: SynapseSidecarSink,
    consents: SynapseConsents = SynapseConsents.NONE,
) {
    @Volatile
    var consents: SynapseConsents = consents

    private val rawCharacteristics = SYNAPSE_RESEARCH_CHARACTERISTIC_UUIDS.values.toSet()

    /**
     * One notification of [characteristicUuid] (any case), received [receivedNanos] after the recording began.
     * Returns whether it was written; a characteristic that is neither a feature, a status nor a raw stream is not.
     */
    fun onNotify(
        characteristicUuid: String,
        data: ByteArray,
        receivedNanos: Long,
    ): Boolean {
        val uuid = characteristicUuid.lowercase()
        return when {
            uuid == SYNAPSE_FEATURE_CHARACTERISTIC_UUID -> {
                if (!consents.signalQuality) return false
                features.append(encodeSynapseSidecarRecord(receivedNanos, maskSynapseFeatureFrame(data, consents)))
                true
            }
            uuid == SYNAPSE_STATUS_CHARACTERISTIC_UUID -> {
                if (!consents.signalQuality) return false
                features.append(encodeSynapseSidecarRecord(receivedNanos, data))
                true
            }
            uuid in rawCharacteristics -> {
                if (!consents.rawSignals) return false
                raw.append(encodeSynapseSidecarRecord(receivedNanos, data))
                true
            }
            else -> false
        }
    }
}
