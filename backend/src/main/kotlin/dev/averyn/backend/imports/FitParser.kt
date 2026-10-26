package dev.averyn.backend.imports

import dev.averyn.domain.Sport
import java.io.InputStream

/** A FIT file bigger than this is not read (the upload limit is the same one as for every other raw file). */
const val MAX_FIT_BYTES = 32 * 1024 * 1024

/** Milliseconds from the Unix epoch to the FIT epoch (1989-12-31T00:00:00Z). */
private const val FIT_EPOCH_OFFSET_MS = 631_065_600_000L

/** FIT timestamps below this are not dates (they count from the start of the device), the spec says. */
private const val MIN_ABSOLUTE_TIMESTAMP = 0x10000000L

/** Semicircles to degrees: 180 / 2^31. */
private const val DEGREES_PER_SEMICIRCLE = 180.0 / 2147483648.0

private const val MESSAGE_FILE_ID = 0
private const val MESSAGE_SESSION = 18
private const val MESSAGE_RECORD = 20
private const val MESSAGE_EVENT = 21

private const val FILE_TYPE_ACTIVITY = 4
private const val EVENT_TIMER = 0

/** The values of `event_type` that stop the timer (stop, stop_all, stop_disable, stop_disable_all). */
private val TIMER_STOP_TYPES = setOf(1, 4, 8, 9)
private const val TIMER_START = 0

private const val FIELD_TIMESTAMP = 253

/** The fields of a `record` message the importer does not read, by name, so the report can say they were there. */
private val IGNORED_RECORD_FIELDS =
    mapOf(
        4 to "cadence",
        5 to "distance",
        6 to "speed",
        7 to "power",
        13 to "temperature",
        73 to "speed",
        // The fields of the running dynamics and the power meters are many; the common ones are named.
        39 to "vertical_oscillation",
        41 to "ground_contact_time",
    )

/** The ids of the sports Averyn records, in the `sport` field of a `session` message. */
private val SPORTS =
    mapOf(
        1 to Sport.RUN,
        2 to Sport.RIDE,
        11 to Sport.WALK,
        17 to Sport.HIKE,
    )

/**
 * Reads a FIT activity file into points, segments and heart rate (TDD-0013).
 *
 * FIT is a binary format: a header, a stream of definition messages (which say how the next data messages are
 * laid out) and data messages, and a CRC. This is a reader for what an import needs — the records (time, position,
 * altitude, heart rate), the timer events (a stop is a pause) and the sport — written from the public file format;
 * every other message is skipped by its size.
 *
 * Like the GPX parser it is strict about what it cannot invent (a file with a wrong CRC, a record without a real
 * time, an unfinished file) and forgiving about what it can keep (a field it does not read is counted and the
 * original file is stored untouched).
 */
class FitParser(
    private val maxPoints: Int = MAX_IMPORT_POINTS,
    private val maxSegments: Int = MAX_IMPORT_SEGMENTS,
) {
    private class Field(
        val number: Int,
        val size: Int,
    )

    private class Definition(
        val global: Int,
        val bigEndian: Boolean,
        val fields: List<Field>,
        val developerBytes: Int,
        val developerFields: Int,
    ) {
        val size: Int = fields.sumOf { it.size } + developerBytes
    }

    private class Invalid(
        val code: String,
        override val message: String,
    ) : RuntimeException(message, null, false, false)

    fun parse(input: InputStream): ImportParseResult {
        val bytes = input.readNBytes(MAX_FIT_BYTES + 1)
        return try {
            read(bytes)
        } catch (e: Invalid) {
            ImportParseResult.Invalid(e.code, e.message)
        } catch (e: IndexOutOfBoundsException) {
            // A definition or a message that runs past the end: the file is cut or damaged.
            ImportParseResult.Invalid("fit_truncated", "the file ends in the middle of a message")
        }
    }

    private fun read(bytes: ByteArray): ImportParseResult {
        if (bytes.size > MAX_FIT_BYTES) throw Invalid("too_large", "the file is too large")
        if (bytes.size < 14) throw Invalid("not_fit", "the file is not a FIT file")
        val headerSize = bytes[0].toInt() and 0xFF
        if ((headerSize != 12 && headerSize != 14) || String(bytes, 8, 4, Charsets.US_ASCII) != ".FIT") {
            throw Invalid("not_fit", "the file is not a FIT file")
        }
        val dataSize = u32(bytes, 4, bigEndian = false)
        val end = headerSize + dataSize
        if (end + 2 > bytes.size) throw Invalid("fit_truncated", "the file is shorter than its header says")
        if (headerSize == 14) {
            val stored = u16(bytes, 12, bigEndian = false).toInt()
            if (stored != 0 && stored != fitCrc(bytes, 0, 12)) {
                throw Invalid("fit_crc", "the header of the file is damaged")
            }
        }
        val storedCrc = u16(bytes, end.toInt(), bigEndian = false).toInt()
        if (storedCrc != 0 && storedCrc != fitCrc(bytes, 0, end.toInt())) {
            throw Invalid("fit_crc", "the file is damaged (its checksum does not match)")
        }

        val definitions = arrayOfNulls<Definition>(16)
        val segments = mutableListOf<List<ImportedPoint>>()
        val heartRates = mutableListOf<ImportedHeartRate>()
        val ignored = mutableMapOf<String, Int>()
        var current = mutableListOf<ImportedPoint>()
        var pointCount = 0
        var stopped = false
        var lastTimestamp = 0L
        var fileType: Int? = null
        var sport: Sport? = null
        var sportSeen = false

        fun count(name: String) {
            ignored[name] = (ignored[name] ?: 0) + 1
        }

        fun closeSegment() {
            if (current.isNotEmpty()) {
                segments += current.toList()
                if (segments.size > maxSegments) throw Invalid("too_many_segments", "the file has too many pauses")
                current = mutableListOf()
            }
        }

        var position = headerSize
        val limit = end.toInt()
        while (position < limit) {
            val header = bytes[position++].toInt() and 0xFF
            when {
                header and 0x80 != 0 -> {
                    // A data message whose header carries the low bits of the time.
                    val definition =
                        definitions[(header shr 5) and 0x03]
                            ?: throw Invalid("fit_structure", "a message uses a layout that was never defined")
                    if (position + definition.size > limit) throw Invalid("fit_truncated", "the file is cut")
                    val offset = header and 0x1F
                    val base = lastTimestamp and 0x1F.inv().toLong()
                    val timestamp = if (offset >= (lastTimestamp and 0x1F)) base + offset else base + offset + 0x20
                    lastTimestamp = timestamp
                    // Only a record can use it in practice; the same handling as a full data message.
                    if (definition.global == MESSAGE_RECORD) {
                        val record = readRecord(bytes, position, definition, timestamp)
                        if (!stopped) {
                            handleRecord(record, current, heartRates, ::count)
                            pointCount += if (record.hasPosition) 1 else 0
                        } else {
                            count("records_while_timer_stopped")
                        }
                    }
                    position += definition.size
                }
                header and 0x40 != 0 -> {
                    val developer = header and 0x20 != 0
                    val local = header and 0x0F
                    // reserved byte, architecture, global message number, number of fields
                    if (position + 5 > limit) throw Invalid("fit_truncated", "the file is cut")
                    position++
                    val big = bytes[position++].toInt() == 1
                    val global = u16(bytes, position, big).toInt()
                    position += 2
                    val fieldCount = bytes[position++].toInt() and 0xFF
                    if (position + fieldCount * 3 > limit) throw Invalid("fit_truncated", "the file is cut")
                    val fields =
                        List(fieldCount) {
                            val field =
                                Field(
                                    number = bytes[position].toInt() and 0xFF,
                                    size =
                                        bytes[position + 1].toInt() and 0xFF,
                                )
                            position += 3
                            field
                        }
                    var developerBytes = 0
                    var developerFields = 0
                    if (developer) {
                        developerFields = bytes[position++].toInt() and 0xFF
                        if (position + developerFields * 3 > limit) throw Invalid("fit_truncated", "the file is cut")
                        repeat(developerFields) {
                            developerBytes += bytes[position + 1].toInt() and 0xFF
                            position += 3
                        }
                    }
                    definitions[local] = Definition(global, big, fields, developerBytes, developerFields)
                }
                else -> {
                    val definition =
                        definitions[header and 0x0F]
                            ?: throw Invalid("fit_structure", "a message uses a layout that was never defined")
                    if (position + definition.size > limit) throw Invalid("fit_truncated", "the file is cut")
                    when (definition.global) {
                        MESSAGE_FILE_ID ->
                            findUnsigned(bytes, position, definition, 0)?.let { fileType = it.toInt() }
                        MESSAGE_SESSION ->
                            if (!sportSeen) {
                                findUnsigned(bytes, position, definition, 5)?.let {
                                    sport = SPORTS[it.toInt()]
                                    sportSeen = true
                                }
                            }
                        MESSAGE_EVENT -> {
                            val event = findUnsigned(bytes, position, definition, 0)?.toInt()
                            val type = findUnsigned(bytes, position, definition, 1)?.toInt()
                            if (event == EVENT_TIMER) {
                                if (type in TIMER_STOP_TYPES) {
                                    closeSegment()
                                    stopped = true
                                } else if (type == TIMER_START) {
                                    stopped = false
                                }
                            }
                        }
                        MESSAGE_RECORD -> {
                            val timestamp = findUnsigned(bytes, position, definition, FIELD_TIMESTAMP)
                            if (timestamp != null) lastTimestamp = timestamp
                            val record = readRecord(bytes, position, definition, timestamp ?: -1L)
                            if (!stopped) {
                                handleRecord(record, current, heartRates, ::count)
                                pointCount += if (record.hasPosition) 1 else 0
                            } else {
                                count("records_while_timer_stopped")
                            }
                        }
                    }
                    if (definition.developerFields > 0) count("developer_data_fields")
                    if (pointCount > maxPoints) throw Invalid("too_many_points", "the file has too many points")
                    position += definition.size
                }
            }
        }
        closeSegment()

        if (fileType != null && fileType != FILE_TYPE_ACTIVITY) {
            throw Invalid("fit_not_activity", "the file is not an activity (a course, a workout or settings)")
        }
        if (segments.isEmpty()) throw Invalid("no_track", "the file has no track points")

        val first = segments.first().first().timeMs
        val last = segments.last().last().timeMs
        // A heart rate outside the track is not part of the activity the track describes: counted, not stored.
        val inside = heartRates.filter { it.timeMs in first..last }
        if (inside.size < heartRates.size) ignored["heart_rate_outside_track"] = heartRates.size - inside.size

        return ImportParseResult.Parsed(ImportedTrack(segments, ignored, inside, sport))
    }

    /** What one `record` message holds, once decoded. */
    private class RecordData(
        val timestampSeconds: Long,
        val latitude: Double?,
        val longitude: Double?,
        val altitudeM: Double?,
        val heartRate: Int?,
        val ignoredNames: List<String>,
    ) {
        val hasPosition: Boolean get() = latitude != null && longitude != null
    }

    private fun readRecord(
        bytes: ByteArray,
        start: Int,
        definition: Definition,
        timestampSeconds: Long,
    ): RecordData {
        var latitude: Double? = null
        var longitude: Double? = null
        var altitude: Double? = null
        var enhancedAltitude: Double? = null
        var heartRate: Int? = null
        val ignoredNames = mutableListOf<String>()
        var at = start
        for (field in definition.fields) {
            val big = definition.bigEndian
            when (field.number) {
                0 -> latitude = semicircles(bytes, at, field.size, big)
                1 -> longitude = semicircles(bytes, at, field.size, big)
                2 -> altitude = altitudeOf(unsigned(bytes, at, field.size, big, 2, 0xFFFFL))
                78 -> enhancedAltitude = altitudeOf(unsigned(bytes, at, field.size, big, 4, 0xFFFFFFFFL))
                3 -> heartRate = unsigned(bytes, at, field.size, big, 1, 0xFFL)?.toInt()
                else ->
                    IGNORED_RECORD_FIELDS[field.number]?.let { name ->
                        // Only a field that holds a value counts: an invalid one is "not there".
                        if (hasValue(bytes, at, field.size)) ignoredNames += name
                    }
            }
            at += field.size
        }
        return RecordData(
            timestampSeconds = timestampSeconds,
            latitude = latitude,
            longitude = longitude,
            altitudeM = enhancedAltitude ?: altitude,
            heartRate = heartRate,
            ignoredNames = ignoredNames.distinct(),
        )
    }

    private fun handleRecord(
        record: RecordData,
        current: MutableList<ImportedPoint>,
        heartRates: MutableList<ImportedHeartRate>,
        count: (String) -> Unit,
    ) {
        if (record.timestampSeconds < MIN_ABSOLUTE_TIMESTAMP) {
            throw Invalid("invalid_time", "a record has no real time")
        }
        val timeMs = record.timestampSeconds * 1000 + FIT_EPOCH_OFFSET_MS
        record.heartRate?.takeIf { it in 1..240 }?.let { heartRates += ImportedHeartRate(timeMs, it) }
        record.ignoredNames.forEach(count)
        val latitude = record.latitude
        val longitude = record.longitude
        if (latitude == null || longitude == null) {
            count("records_without_position")
            return
        }
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            throw Invalid("invalid_coordinate", "a record has a coordinate out of range")
        }
        current += ImportedPoint(timeMs, latitude, longitude, record.altitudeM)
    }

    // ------------------------------------------------------------------------------------------------ decoding

    /** The unsigned value of field [number] of a message, or null when it is not there or holds the invalid value. */
    private fun findUnsigned(
        bytes: ByteArray,
        start: Int,
        definition: Definition,
        number: Int,
    ): Long? {
        var at = start
        for (field in definition.fields) {
            if (field.number == number) {
                val invalid =
                    when (field.size) {
                        1 -> 0xFFL
                        2 -> 0xFFFFL
                        4 -> 0xFFFFFFFFL
                        else -> return null
                    }
                return unsigned(bytes, at, field.size, definition.bigEndian, field.size, invalid)
            }
            at += field.size
        }
        return null
    }

    /** An unsigned integer of [expected] bytes, or null when the field is another size or holds the invalid value. */
    private fun unsigned(
        bytes: ByteArray,
        at: Int,
        size: Int,
        bigEndian: Boolean,
        expected: Int,
        invalid: Long,
    ): Long? {
        if (size != expected) return null
        val value =
            when (size) {
                1 -> (bytes[at].toInt() and 0xFF).toLong()
                2 -> u16(bytes, at, bigEndian)
                else -> u32(bytes, at, bigEndian)
            }
        return if (value == invalid) null else value
    }

    /** A position in semicircles (a signed 32-bit integer) as degrees, or null for the invalid value. */
    private fun semicircles(
        bytes: ByteArray,
        at: Int,
        size: Int,
        bigEndian: Boolean,
    ): Double? {
        if (size != 4) return null
        val raw = u32(bytes, at, bigEndian).toInt()
        return if (raw == Int.MAX_VALUE) null else raw * DEGREES_PER_SEMICIRCLE
    }

    /** The altitude of the FIT profile: the value is scaled by 5 and offset by 500 m. */
    private fun altitudeOf(raw: Long?): Double? = raw?.let { it / 5.0 - 500.0 }

    private fun hasValue(
        bytes: ByteArray,
        at: Int,
        size: Int,
    ): Boolean = (0 until size).any { bytes[at + it] != 0xFF.toByte() }

    private fun u16(
        bytes: ByteArray,
        at: Int,
        bigEndian: Boolean,
    ): Long {
        val a = bytes[at].toInt() and 0xFF
        val b = bytes[at + 1].toInt() and 0xFF
        return (if (bigEndian) (a shl 8) or b else (b shl 8) or a).toLong()
    }

    private fun u32(
        bytes: ByteArray,
        at: Int,
        bigEndian: Boolean,
    ): Long {
        var value = 0L
        for (i in 0 until 4) {
            val b = (bytes[if (bigEndian) at + i else at + 3 - i].toInt() and 0xFF).toLong()
            value = (value shl 8) or b
        }
        return value
    }
}

private val CRC_TABLE =
    intArrayOf(
        0x0000,
        0xCC01,
        0xD801,
        0x1400,
        0xF001,
        0x3C00,
        0x2800,
        0xE401,
        0xA001,
        0x6C00,
        0x7800,
        0xB401,
        0x5000,
        0x9C01,
        0x8801,
        0x4400,
    )

/** The CRC-16 of the FIT format (the ARC polynomial, computed a nibble at a time) over `bytes[from until to]`. */
internal fun fitCrc(
    bytes: ByteArray,
    from: Int,
    to: Int,
): Int {
    var crc = 0
    for (i in from until to) {
        val byte = bytes[i].toInt() and 0xFF
        var tmp = CRC_TABLE[crc and 0xF]
        crc = (crc shr 4) and 0x0FFF
        crc = crc xor tmp xor CRC_TABLE[byte and 0xF]
        tmp = CRC_TABLE[crc and 0xF]
        crc = (crc shr 4) and 0x0FFF
        crc = crc xor tmp xor CRC_TABLE[(byte shr 4) and 0xF]
    }
    return crc
}
