package dev.averyn.sensors

/** The only feature-frame version this parser understands; any other is reported, never read. */
const val SYNAPSE_FEATURE_FRAME_VERSION = 2

/**
 * The bytes of a version-2 frame. A frame may be longer (the contract can append fields without a new version);
 * a shorter one is cut and is not read.
 */
const val SYNAPSE_FEATURE_FRAME_BYTES = 27

/**
 * The stress class of the band. `UNKNOWN` (3) is "the model ran and has no class"; a model that did not run is
 * `null` on the frame (the wire value 255), and is not shown at all (ADR-0030).
 */
enum class SynapseStress { BASELINE, STRESS, ARTIFACT, UNKNOWN }

/** The bits of `sensor_health`: what the band found on its board. */
object SynapseHealth {
    const val ECG = 0x01
    const val PPG = 0x02
    const val IMU = 0x04
    const val TEMP = 0x08
    const val POWER = 0x10
}

/**
 * One feature frame, decoded (ADR-0026 level L1, contract 2.2). A field the band marks "not available" is
 * **null**, never zero: a zero would be read as a measurement (a heart rate of 0, a perfect or a terrible signal).
 * A value outside its physical range is null too.
 *
 * These are values a Synapse device computed. Averyn did not compute them and cannot reproduce them, so they are
 * stored as imported data with the producer's own version, and never as an Averyn metric (ADR-0014).
 */
data class SynapseFeatureFrame(
    /** The counter of the frame, 16 bits, wrapping: a gap is a lost frame. */
    val sequence: Int,
    /** The band's own clock in **milliseconds** (32 bits, wraps after 49.7 days); not the raw frames' microseconds. */
    val deviceMillis: Long,
    val heartRateBpm: Int?,
    /** The last beat-to-beat interval, in milliseconds (the wire carries 1/1024 s). Needs the `rr_recording` scope. */
    val rrLastMs: Double?,
    /** How many intervals happened since the previous frame; more than one means the others were not carried. */
    val rrCount: Int?,
    /** PPG signal quality, 0.0 to 1.0. */
    val ppgQuality: Double?,
    val ecgQuality: Double?,
    val motionLevel: Double?,
    /** Probability that the PPG is corrupted by motion, 0.0 to 1.0. */
    val motionArtifactProbability: Double?,
    val stress: SynapseStress?,
    val batteryPercent: Int?,
    /** Read only when the band has a power monitor (`sensor_health` POWER); otherwise null, never "not charging". */
    val charging: Boolean?,
    val temperatureCelsius: Double?,
    /** The band's own RMSSD and SDNN. They need the `hrv_analysis` scope. */
    val rmssdMs: Int?,
    val sdnnMs: Int?,
    /** 0 generic, 1 running, 2 cycling, 3 swimming, 4 sleep; null when not set or not one of those. */
    val sport: Int?,
    /** The raw `sensor_health` byte; see [SynapseHealth]. */
    val sensorHealth: Int,
    val leadOff: Boolean,
    val transmitting: Boolean,
    val clockWrapped: Boolean,
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

/** A byte scaled 0..254 to 0.0..1.0; 255 is "not available" (so the scale is 1/254, not 1/255). */
private fun fraction(raw: Int): Double? = raw.takeIf { it != 0xFF }?.let { it / 254.0 }

/**
 * Decodes one notification of the v2 feature characteristic (contract 2.2, packed little-endian, 27 bytes):
 *
 * `version u8, flags u8, sequence u16, t_dev_ms u32, heart_rate u16, rr_last u16 (1/1024 s), rr_count u8,
 * ppg_sqi u8, ecg_quality u8, motion_level u8, stress_level u8, battery u8, temperature s16 (0.01 °C),
 * rmssd u16, sdnn u16, sport u8, sensor_health u8, motion_artifact u8`.
 *
 * Pure: bytes in, a result out. It never reads past the buffer and never throws on a short or foreign frame.
 */
fun parseSynapseFeatureFrame(data: ByteArray): SynapseFrameResult {
    if (data.isEmpty()) return SynapseFrameResult.Truncated(0)
    val version = data[0].toInt() and 0xFF
    if (version != SYNAPSE_FEATURE_FRAME_VERSION) return SynapseFrameResult.UnknownVersion(version)
    if (data.size < SYNAPSE_FEATURE_FRAME_BYTES) return SynapseFrameResult.Truncated(data.size)

    fun u8(at: Int) = data[at].toInt() and 0xFF

    fun u16(at: Int) = u8(at) or (u8(at + 1) shl 8)

    fun u32(at: Int) = (u16(at).toLong()) or (u16(at + 2).toLong() shl 16)

    val flags = u8(1)
    val health = u8(25)
    val stress =
        when (u8(16)) {
            0 -> SynapseStress.BASELINE
            1 -> SynapseStress.STRESS
            2 -> SynapseStress.ARTIFACT
            3 -> SynapseStress.UNKNOWN
            else -> null // 255 = the model did not run; anything else is not a class either
        }

    return SynapseFrameResult.Frame(
        SynapseFeatureFrame(
            sequence = u16(2),
            deviceMillis = u32(4),
            heartRateBpm = u16(8).takeIf { it in 1..300 },
            rrLastMs = u16(10).takeIf { it != 0xFFFF }?.let { it * 1000.0 / 1024.0 }?.takeIf { it in 1.0..3000.0 },
            rrCount = u8(12).takeIf { it != 0xFF },
            ppgQuality = fraction(u8(13)),
            ecgQuality = fraction(u8(14)),
            motionLevel = fraction(u8(15)),
            motionArtifactProbability = fraction(u8(26)),
            stress = stress,
            batteryPercent = u8(17).takeIf { it in 0..100 },
            charging = if (health and SynapseHealth.POWER != 0) (flags and 0x01 != 0) else null,
            temperatureCelsius =
                u16(18)
                    .toShort()
                    .toInt()
                    .takeIf { it != 0x7FFF }
                    ?.let { it / 100.0 },
            rmssdMs = u16(20).takeIf { it != 0xFFFF },
            sdnnMs = u16(22).takeIf { it != 0xFFFF },
            sport = u8(24).takeIf { it in 0..4 },
            sensorHealth = health,
            leadOff = flags and 0x02 != 0,
            transmitting = flags and 0x04 != 0,
            clockWrapped = flags and 0x08 != 0,
        ),
    )
}
