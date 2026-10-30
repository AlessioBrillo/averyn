package dev.averyn.sensors

/** The service of the consumer feature stream of a Synapse band (contract `gatt_v1`, ADR-0026). */
const val SYNAPSE_FEATURE_SERVICE_UUID = "f9e08ffd-3d34-441f-97a6-1bf0958123f1"

/** The characteristic that notifies the feature frames. */
const val SYNAPSE_FEATURE_CHARACTERISTIC_UUID = "db4307e5-6cb3-4152-a0e2-81c3921168e9"

/** The only frame version this parser understands; any other is reported, never read. */
const val SYNAPSE_FEATURE_FRAME_VERSION = 1

/**
 * The bytes of a version-1 frame. A frame may be longer (a later firmware can append fields without a new version
 * only if the contract says so); a shorter one is cut and is not read.
 */
const val SYNAPSE_FEATURE_FRAME_V1_BYTES = 23

/** The stress class of the band. `unknown` (3) is not a class: it is "the model has not run", and is null. */
enum class SynapseStress { BASELINE, STRESS, ARTIFACT }

/**
 * One notification of the feature stream, decoded (ADR-0026 level L1). A field the band marks "not available" is
 * **null**, never zero: a zero would be read as a measurement (a heart rate of 0, a perfect or a terrible signal).
 *
 * These are values a Synapse device computed. Averyn did not compute them and cannot reproduce them, so they are
 * stored as imported data with the producer's own version, and never as an Averyn metric (ADR-0014).
 */
data class SynapseFeatureFrame(
    /** The counter of the frame, 16 bits, wrapping: a gap is a lost frame. */
    val sequence: Int,
    val heartRateBpm: Int?,
    val rrIntervalMs: Int?,
    /** PPG signal quality, 0.0 to 1.0. */
    val ppgQuality: Double?,
    /** Probability that the PPG is corrupted by motion, 0.0 to 1.0. */
    val motionArtifactProbability: Double?,
    val ecgQuality: Double?,
    val stress: SynapseStress?,
    val motionLevel: Double?,
    val batteryPercent: Int?,
    val charging: Boolean?,
    /** 0 generic, 1 running, 2 cycling, 3 swimming, 4 sleep; null when the value is not one of those. */
    val sport: Int?,
    val cadenceRpm: Int?,
    val temperatureCelsius: Double?,
    val rmssdMs: Int?,
    val sdnnMs: Int?,
)

/**
 * What came out of one notification. A sealed class and not an interface: the Swift export of a sealed interface
 * is a protocol whose nested cases cannot be named (see [dev.averyn.tracking.RecoveryResult]).
 */
sealed class SynapseFrameResult {
    data class Frame(
        val frame: SynapseFeatureFrame,
    ) : SynapseFrameResult()

    /** A version this parser does not know: counted as a loss by the caller, never interpreted (ADR-0014). */
    data class UnknownVersion(
        val version: Int,
    ) : SynapseFrameResult()

    /** Fewer bytes than a frame of this version has: cut by the link, not read. */
    data class Truncated(
        val size: Int,
    ) : SynapseFrameResult()
}

/**
 * Decodes one notification of the feature stream (contract `gatt_v1`, the packed little-endian layout of
 * `synapse_feature_stream_t`):
 *
 * `version u8, sequence u16, heart_rate u16, rr_interval_ms u16, ppg_sqi u8, ppg_map u8, ecg_quality u8,
 * stress u8, motion u8, battery u8, charging u8, sport u8, cadence u16, temperature s16 (0.01 °C),
 * rmssd u16, sdnn u16`.
 *
 * Pure: bytes in, a result out. It never reads past the buffer and never throws on a short or foreign frame.
 */
fun parseSynapseFeatureFrame(data: ByteArray): SynapseFrameResult {
    if (data.isEmpty()) return SynapseFrameResult.Truncated(0)
    val version = data[0].toInt() and 0xFF
    if (version != SYNAPSE_FEATURE_FRAME_VERSION) return SynapseFrameResult.UnknownVersion(version)
    if (data.size < SYNAPSE_FEATURE_FRAME_V1_BYTES) return SynapseFrameResult.Truncated(data.size)

    fun u8(at: Int) = data[at].toInt() and 0xFF

    fun u16(at: Int) = u8(at) or (u8(at + 1) shl 8)

    fun s16(at: Int) = u16(at).toShort().toInt()

    /** A byte scaled 0..255 to 0.0..1.0; 0xFF is "not available". */
    fun scaled(at: Int): Double? = u8(at).takeIf { it != 0xFF }?.let { it / 255.0 }

    /** A 16-bit value where 0xFFFF is "not available". */
    fun u16OrNull(at: Int): Int? = u16(at).takeIf { it != 0xFFFF }

    val stress =
        when (u8(10)) {
            0 -> SynapseStress.BASELINE
            1 -> SynapseStress.STRESS
            2 -> SynapseStress.ARTIFACT
            else -> null // 3 = unknown, and anything else is not a class either
        }
    val battery = u8(12).takeIf { it in 0..100 }
    val charging =
        when (u8(13)) {
            0 -> false
            1 -> true
            else -> null
        }
    val sport = u8(14).takeIf { it in 0..4 }
    val temperature = s16(17).takeIf { it != 0x7FFF }?.let { it / 100.0 }

    return SynapseFrameResult.Frame(
        SynapseFeatureFrame(
            sequence = u16(1),
            heartRateBpm = u16OrNull(3)?.takeIf { it in 1..300 },
            rrIntervalMs = u16OrNull(5)?.takeIf { it in 1..3000 },
            ppgQuality = scaled(7),
            motionArtifactProbability = scaled(8),
            ecgQuality = scaled(9),
            stress = stress,
            motionLevel = scaled(11),
            batteryPercent = battery,
            charging = charging,
            sport = sport,
            cadenceRpm = u16OrNull(15),
            temperatureCelsius = temperature,
            rmssdMs = u16OrNull(19),
            sdnnMs = u16OrNull(21),
        ),
    )
}

/**
 * Counts the frames the link lost, from the 16-bit counter (ADR-0026: a gap is counted with its reason, never
 * silently dropped). A frame whose counter is just behind the last one (a repeat, or an old one) is counted apart
 * and does not move the counter back. A counter far behind is a restart of the band: the stream starts again from
 * there, and the frames of the old run are not counted as lost.
 */
class SynapseSequenceTracker {
    private companion object {
        /** A step back of up to this many frames is a repeat or a late frame; a longer one is a restart. */
        const val OUT_OF_ORDER_WINDOW = 64
    }

    private var last: Int? = null

    /** Frames that never arrived, in total. */
    var lostFrames: Int = 0
        private set

    /** Frames that arrived with a counter at or behind the last one. */
    var outOfOrderFrames: Int = 0
        private set

    /** How many times the counter restarted (the band rebooted or reconnected). */
    var restarts: Int = 0
        private set

    /** Notes the sequence of a frame; returns how many frames were missing just before it. */
    fun onFrame(sequence: Int): Int {
        val previous = last
        last = sequence
        if (previous == null) return 0
        val missed = (sequence - previous - 1) and 0xFFFF
        // A distance over half the counter is a step backwards (or a repeat), not 60 000 lost frames.
        if (missed >= 0x8000) {
            // ponytail: a fixed window; a band with a different frame rate would need it from the contract.
            if (((previous - sequence) and 0xFFFF) > OUT_OF_ORDER_WINDOW) {
                restarts++
                return 0
            }
            outOfOrderFrames++
            last = previous
            return 0
        }
        lostFrames += missed
        return missed
    }
}
