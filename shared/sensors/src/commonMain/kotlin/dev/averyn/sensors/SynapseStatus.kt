package dev.averyn.sensors

/** The bytes of a version-1 status record (the status characteristic has its own version, independent of the feature). */
const val SYNAPSE_STATUS_BYTES = 40

/** The only status version this parser understands. */
const val SYNAPSE_STATUS_VERSION = 1

/**
 * The status record of the band, decoded (contract 2.2). The counters are the band's own account of what it lost:
 * together with the sequence holes they tell a bench operator where data went (ring overflow, refused notifications,
 * PPG samples skipped, power telemetry dropped). They are shown as markers on the signal, never hidden.
 */
data class SynapseStatus(
    /** 0, 1 or 2: the acquisition tier the band is in. */
    val tier: Int,
    /** See [SynapseHealth]. */
    val sensorHealth: Int,
    val transmitting: Boolean,
    val leadOff: Boolean,
    val mtu: Int,
    val uptimeSeconds: Long,
    val epochWraps: Int,
    val ringOverflow: Long,
    val staleSamples: Long,
    val droppedFrames: Long,
    val readErrors: Long,
    val firmwareBuild: Long,
    val ppgSkipped: Long,
    val powerDropped: Long,
)

/** What came out of one status notification. */
sealed class SynapseStatusResult {
    data class Status(
        val status: SynapseStatus,
    ) : SynapseStatusResult()

    data class UnknownVersion(
        val version: Int,
    ) : SynapseStatusResult()

    data class Truncated(
        val size: Int,
    ) : SynapseStatusResult()
}

/**
 * Decodes one status notification (40 bytes): `version u8, tier u8, sensor_health u8, flags u8, mtu u16,
 * uptime_s u32, epoch_wraps u16, ring_overflow u32, stale_samples u32, dropped_frames u32, read_errors u32,
 * fw_build u32, ppg_skipped u32, power_dropped u32`. Never throws.
 */
fun parseSynapseStatus(data: ByteArray): SynapseStatusResult {
    if (data.isEmpty()) return SynapseStatusResult.Truncated(0)
    val version = data[0].toInt() and 0xFF
    if (version != SYNAPSE_STATUS_VERSION) return SynapseStatusResult.UnknownVersion(version)
    if (data.size < SYNAPSE_STATUS_BYTES) return SynapseStatusResult.Truncated(data.size)

    fun u8(at: Int) = data[at].toInt() and 0xFF

    fun u16(at: Int) = u8(at) or (u8(at + 1) shl 8)

    fun u32(at: Int) = u16(at).toLong() or (u16(at + 2).toLong() shl 16)

    val flags = u8(3)
    return SynapseStatusResult.Status(
        SynapseStatus(
            tier = u8(1),
            sensorHealth = u8(2),
            transmitting = flags and 0x04 != 0,
            leadOff = flags and 0x02 != 0,
            mtu = u16(4),
            uptimeSeconds = u32(6),
            epochWraps = u16(10),
            ringOverflow = u32(12),
            staleSamples = u32(16),
            droppedFrames = u32(20),
            readErrors = u32(24),
            firmwareBuild = u32(28),
            ppgSkipped = u32(32),
            powerDropped = u32(36),
        ),
    )
}
