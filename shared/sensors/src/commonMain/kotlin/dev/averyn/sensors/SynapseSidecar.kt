package dev.averyn.sensors

/**
 * The sidecar files the phone writes while recording a band (ADR-0030): what the band sent, as received, one
 * append-only file per kind, so a better filter never needs a new recording. Each record is
 *
 * `u16 length | i64 received_ns | frame bytes` (little endian)
 *
 * where `received_ns` is the phone's monotonic clock since the recording began and the frame is exactly what was
 * notified. `<id>.synapse.bin` holds the consumer records (feature 27 bytes, status 40, told apart by length);
 * `<id>.synapse-raw.bin` holds the raw frames.
 */
const val SYNAPSE_SIDECAR_RECORD_HEADER_BYTES = 10

/** The longest frame a record can carry: the length is 16 bits. */
const val SYNAPSE_SIDECAR_MAX_FRAME_BYTES = 0xFFFF

/** One record of a sidecar. */
class SynapseSidecarRecord(
    val receivedNanos: Long,
    val frame: ByteArray,
)

/** The bytes to append to a sidecar for one notification. */
fun encodeSynapseSidecarRecord(
    receivedNanos: Long,
    frame: ByteArray,
): ByteArray {
    require(receivedNanos >= 0) { "reception time is relative to the start of the recording" }
    require(frame.size <= SYNAPSE_SIDECAR_MAX_FRAME_BYTES) { "a frame of ${frame.size} bytes does not fit a record" }
    val out = ByteArray(SYNAPSE_SIDECAR_RECORD_HEADER_BYTES + frame.size)
    out[0] = (frame.size and 0xFF).toByte()
    out[1] = ((frame.size ushr 8) and 0xFF).toByte()
    for (i in 0 until 8) out[2 + i] = ((receivedNanos ushr (8 * i)) and 0xFF).toByte()
    frame.copyInto(out, SYNAPSE_SIDECAR_RECORD_HEADER_BYTES)
    return out
}

/** A sidecar read to its end. */
class SynapseSidecar(
    val records: List<SynapseSidecarRecord>,
    /**
     * Bytes after the last whole record. An append-only file can end inside a record if the app was killed while
     * writing: they are reported, not read.
     */
    val trailingBytes: Int,
)

/** Reads a sidecar. Never throws: a cut tail is [SynapseSidecar.trailingBytes]. */
fun readSynapseSidecar(blob: ByteArray): SynapseSidecar {
    val records = ArrayList<SynapseSidecarRecord>()
    var at = 0
    while (blob.size - at >= SYNAPSE_SIDECAR_RECORD_HEADER_BYTES) {
        val length = (blob[at].toInt() and 0xFF) or ((blob[at + 1].toInt() and 0xFF) shl 8)
        if (blob.size - at - SYNAPSE_SIDECAR_RECORD_HEADER_BYTES < length) break
        var nanos = 0L
        for (i in 0 until 8) nanos = nanos or ((blob[at + 2 + i].toLong() and 0xFF) shl (8 * i))
        val from = at + SYNAPSE_SIDECAR_RECORD_HEADER_BYTES
        records += SynapseSidecarRecord(nanos, blob.copyOfRange(from, from + length))
        at = from + length
    }
    return SynapseSidecar(records, blob.size - at)
}
