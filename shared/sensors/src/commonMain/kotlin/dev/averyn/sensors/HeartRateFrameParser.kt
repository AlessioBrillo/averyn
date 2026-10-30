package dev.averyn.sensors

// The ranges the sample model accepts (HeartRateSample, RrInterval): a value outside is reported, not thrown.
private val BPM_RANGE = 0..300
private val RR_RANGE_MS = 1..3000

/**
 * Parses one or more back-to-back `0x2A37` notifications from [data], starting at [offset].
 * [elapsedRealtimeMs] is stamped on every produced value (the parser never reads a clock itself;
 * the caller supplies it, keeping this pure and testable — TDD-0001 §4).
 *
 * Frame layout (Bluetooth spec):
 *   [flags: u8][hr: u8|u16][energy_expended: u16?][rr_intervals: u16...]
 *   flags bit 0: hr value format (0 = uint8, 1 = uint16)
 *   flags bit 1-2: sensor contact status (00 = not supported, 01 = detected, 10 = not detected, 11 = reserved)
 *   flags bit 3: energy expended present
 *   flags bit 4: RR intervals present
 *
 * Never reads past the buffer; a truncated frame is reported as [ParseResult.Incomplete] and consumes
 * no bytes, so the caller can retry with more data.
 *
 * Returns the parsed results and how many input bytes were consumed. Callers loop until consumed == 0
 * (no more complete frames) or the buffer is exhausted.
 */
fun parseHeartRateFrames(
    data: ByteArray,
    offset: Int,
    elapsedRealtimeMs: Long,
    timeMs: Long,
): Pair<List<ParseResult>, Int> {
    val results = mutableListOf<ParseResult>()
    var pos = offset
    while (pos < data.size) {
        val frameStart = pos
        val flags = data[pos].toInt() and 0xFF
        val uint16Format = flags and 0x01 == 0x01
        val contactBits = (flags shr 1) and 0x03
        val energyPresent = flags and 0x08 == 0x08
        val rrPresent = flags and 0x10 == 0x10

        val contact: Boolean? =
            when (contactBits) {
                0x00 -> null
                0x01 -> true
                0x02 -> false
                else -> null // 0x03 reserved
            }

        pos++ // flags
        val hrSize = if (uint16Format) 2 else 1
        if (pos + hrSize > data.size) {
            // Truncated frame: consume nothing so the caller can retry with more bytes.
            results += ParseResult.Incomplete(hrSize - (data.size - pos))
            return results to frameStart
        }
        val bpm =
            if (uint16Format) {
                (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
            } else {
                data[pos].toInt() and 0xFF
            }
        pos += hrSize

        var energyExpended: Int? = null
        if (energyPresent) {
            if (pos + 2 > data.size) {
                results += ParseResult.Incomplete(2 - (data.size - pos))
                return results to frameStart
            }
            energyExpended = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
            pos += 2
        }

        val rrIntervals = mutableListOf<RrInterval>()
        val invalid = mutableListOf<ParseResult.Invalid>()
        if (rrPresent) {
            while (pos + 2 <= data.size) {
                // The unit on the wire is 1/1024 s (Bluetooth HRS), not milliseconds.
                val raw = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
                val rrMs = (raw * 1000 + 512) / 1024
                if (rrMs in RR_RANGE_MS) {
                    rrIntervals += RrInterval(timeMs, elapsedRealtimeMs, rrMs)
                } else {
                    invalid += ParseResult.Invalid("rr out of range: $rrMs ms")
                }
                pos += 2
            }
            // An odd trailing byte means a truncated last RR: don't consume it.
            if (pos < data.size) {
                results += ParseResult.Incomplete(2)
                return results to frameStart
            }
        }

        // A bpm the sample model rejects leaves the frame without a sample; the RR intervals still count.
        val heartRate =
            if (bpm in BPM_RANGE) {
                HeartRateSample(timeMs, elapsedRealtimeMs, bpm, contact)
            } else {
                invalid += ParseResult.Invalid("bpm out of range: $bpm")
                null
            }
        results += invalid
        results +=
            ParseResult.Complete(
                heartRate = heartRate,
                rrIntervals = rrIntervals,
                energyExpended = energyExpended,
                consumedBytes = pos - frameStart,
            )
    }
    return results to pos
}
