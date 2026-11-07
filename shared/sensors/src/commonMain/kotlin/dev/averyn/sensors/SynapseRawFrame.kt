package dev.averyn.sensors

/** The raw frame header is 12 bytes; a frame carries at most 244 bytes (contract `raw_frame_v1.json`). */
const val SYNAPSE_RAW_HEADER_BYTES = 12

/** The only raw frame version this decoder understands. */
const val SYNAPSE_RAW_FRAME_VERSION = 1

/** The streams of the research service. Ids are the wire values of the frame header. */
enum class SynapseRawStream(
    val id: Int,
) {
    ECG(1),
    PPG(2),
    ACC(3),
    GYRO(4),
    MAG(5),
    TEMP(6),
    POWER(7),
    SYNC(16),
    ;

    /** The lower-case name the contract and the fixtures use. */
    val wireName: String get() = name.lowercase()

    companion object {
        fun fromId(id: Int): SynapseRawStream? = entries.firstOrNull { it.id == id }
    }
}

/** The sample formats of a raw frame: the wire code and the width of one value. */
enum class SynapseRawFormat(
    val code: Int,
    val bytes: Int,
) {
    S8(1, 1),
    S16(2, 2),
    S32(3, 4),
    ;

    companion object {
        fun fromCode(code: Int): SynapseRawFormat? = entries.firstOrNull { it.code == code }
    }
}

/**
 * How a stream is laid out and shown (contract `gatt_v2.json` "stream_metadata"). Units come from here and nowhere
 * else: no screen writes a unit by hand (ADR-0030). A wire integer times [displayPerWire] is what a screen shows,
 * in [displayUnit] (a gyroscope travels in mrad/s and is shown in degrees per second).
 */
data class SynapseStreamInfo(
    val stream: SynapseRawStream,
    val rateHz: Int,
    val channelNames: List<String>,
    val channelUnits: List<String>,
    val displayUnit: String,
    val displayPerWire: Double,
) {
    val channels: Int get() = channelNames.size
}

/** The streams Averyn shows. TEMP and SYNC exist on the wire but carry nothing for a user. */
val SYNAPSE_STREAMS: Map<SynapseRawStream, SynapseStreamInfo> =
    listOf(
        SynapseStreamInfo(SynapseRawStream.ECG, 500, listOf("ecg"), listOf("uV"), "uV", 1.0),
        SynapseStreamInfo(SynapseRawStream.PPG, 50, listOf("red", "ir"), listOf("count", "count"), "count", 1.0),
        SynapseStreamInfo(SynapseRawStream.ACC, 50, listOf("x", "y", "z"), listOf("mg", "mg", "mg"), "mg", 1.0),
        SynapseStreamInfo(
            SynapseRawStream.GYRO,
            50,
            listOf("x", "y", "z"),
            listOf("dps", "dps", "dps"),
            "dps",
            0.057295779513082325,
        ),
        SynapseStreamInfo(SynapseRawStream.MAG, 50, listOf("x", "y", "z"), listOf("uT", "uT", "uT"), "uT", 0.01),
        SynapseStreamInfo(
            SynapseRawStream.POWER,
            1,
            listOf("voltage", "current", "power", "battery"),
            listOf("mV", "mA", "mW", "percent"),
            "mixed",
            1.0,
        ),
    ).associateBy { it.stream }

/** The header of one raw frame. */
data class SynapseRawHeader(
    val stream: SynapseRawStream,
    val sequence: Int,
    /** The low 32 bits of the band's **microsecond** counter at the first sample of the frame. */
    val timestampUs: Long,
    /** The 32-bit counter wrapped since the previous frame of this stream: add 2^32 to the stream's epoch. */
    val timestampWrapped: Boolean,
    /** The number of VALUES (channel-minor), not of multi-channel samples. */
    val valueCount: Int,
    val format: SynapseRawFormat,
    /** Samples the band reports it lost for this stream since the previous transmitted frame (saturates at 255). */
    val dropped: Int,
)

/** Why a raw frame was refused. Each is counted and the frame is never interpreted (contract: "counted as a loss"). */
enum class SynapseRawInvalid(
    val wireName: String,
) {
    TOO_SHORT("too_short"),
    UNKNOWN_VERSION("unknown_version"),
    UNKNOWN_STREAM("unknown_stream"),
    UNKNOWN_FORMAT("unknown_format"),
    ZERO_SAMPLES("zero_samples"),
    TRUNCATED("truncated"),
}

/** What came out of one raw notification. */
sealed class SynapseRawResult {
    class Frame(
        val header: SynapseRawHeader,
        /** Channel-minor, sign-extended. */
        val values: IntArray,
    ) : SynapseRawResult()

    data class Invalid(
        val reason: SynapseRawInvalid,
    ) : SynapseRawResult()
}

/**
 * Decodes one raw frame: a 12-byte header (`version u8, stream u8, sequence u16, timestamp_us u32, flags u8,
 * sample_count u8, sample_format u8, dropped u8`) and `sample_count` values, little-endian two's complement. The
 * checks run in the order of the contract's reference codec, so the same bad frame has the same reason everywhere.
 * Bytes after the declared values are ignored. Never throws.
 */
fun decodeSynapseRawFrame(data: ByteArray): SynapseRawResult {
    if (data.size < SYNAPSE_RAW_HEADER_BYTES) return SynapseRawResult.Invalid(SynapseRawInvalid.TOO_SHORT)

    fun u8(at: Int) = data[at].toInt() and 0xFF

    fun u16(at: Int) = u8(at) or (u8(at + 1) shl 8)

    if (u8(0) != SYNAPSE_RAW_FRAME_VERSION) return SynapseRawResult.Invalid(SynapseRawInvalid.UNKNOWN_VERSION)
    val stream = SynapseRawStream.fromId(u8(1)) ?: return SynapseRawResult.Invalid(SynapseRawInvalid.UNKNOWN_STREAM)
    val format = SynapseRawFormat.fromCode(u8(10)) ?: return SynapseRawResult.Invalid(SynapseRawInvalid.UNKNOWN_FORMAT)
    val count = u8(9)
    if (count == 0) return SynapseRawResult.Invalid(SynapseRawInvalid.ZERO_SAMPLES)
    val needed = SYNAPSE_RAW_HEADER_BYTES + count * format.bytes
    if (data.size < needed) return SynapseRawResult.Invalid(SynapseRawInvalid.TRUNCATED)

    val values = IntArray(count)
    var at = SYNAPSE_RAW_HEADER_BYTES
    for (i in 0 until count) {
        values[i] =
            when (format) {
                SynapseRawFormat.S8 -> data[at].toInt()
                SynapseRawFormat.S16 -> u16(at).toShort().toInt()
                SynapseRawFormat.S32 -> u16(at) or (u16(at + 2) shl 16)
            }
        at += format.bytes
    }
    val timestamp = u16(4).toLong() or (u16(6).toLong() shl 16)
    return SynapseRawResult.Frame(
        SynapseRawHeader(
            stream = stream,
            sequence = u16(2),
            timestampUs = timestamp,
            timestampWrapped = u8(8) and 0x01 != 0,
            valueCount = count,
            format = format,
            dropped = u8(11),
        ),
        values,
    )
}
