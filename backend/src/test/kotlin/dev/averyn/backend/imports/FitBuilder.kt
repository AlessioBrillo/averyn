package dev.averyn.backend.imports

import java.io.ByteArrayOutputStream

/** One field of a FIT definition message: its number, its size in bytes and its base type. */
data class FitField(
    val number: Int,
    val size: Int,
    val baseType: Int,
)

/**
 * Writes FIT files for the tests, from the public file format, independently of the parser: a header, the messages
 * and the CRC. It writes only what the tests need (definitions, data messages, compressed timestamps, developer
 * fields), and it can damage a file on purpose.
 */
class FitBuilder(
    private val bigEndian: Boolean = false,
) {
    private val body = ByteArrayOutputStream()

    /** A definition message for local type [local]. */
    fun definition(
        local: Int,
        global: Int,
        fields: List<FitField>,
        developerFields: List<Int> = emptyList(),
    ): FitBuilder {
        body.write(0x40 or (if (developerFields.isNotEmpty()) 0x20 else 0) or local)
        body.write(0) // reserved
        body.write(if (bigEndian) 1 else 0)
        body.write(u16(global))
        body.write(fields.size)
        fields.forEach {
            body.write(it.number)
            body.write(it.size)
            body.write(it.baseType)
        }
        if (developerFields.isNotEmpty()) {
            body.write(developerFields.size)
            developerFields.forEach {
                body.write(0) // field number
                body.write(it) // size
                body.write(0) // developer data index
            }
        }
        return this
    }

    /** A data message for local type [local]: the raw bytes of its fields, in the order of the definition. */
    fun data(
        local: Int,
        vararg values: ByteArray,
    ): FitBuilder {
        body.write(local)
        values.forEach { body.write(it) }
        return this
    }

    /** A data message with a compressed timestamp header: [offset] is the low five bits of the time. */
    fun compressedData(
        local: Int,
        offset: Int,
        vararg values: ByteArray,
    ): FitBuilder {
        body.write(0x80 or (local shl 5) or (offset and 0x1F))
        values.forEach { body.write(it) }
        return this
    }

    fun u8(value: Int) = byteArrayOf(value.toByte())

    fun u16(value: Int): ByteArray =
        if (bigEndian) {
            byteArrayOf((value shr 8).toByte(), value.toByte())
        } else {
            byteArrayOf(value.toByte(), (value shr 8).toByte())
        }

    fun u32(value: Long): ByteArray {
        val b = ByteArray(4) { ((value shr (8 * it)) and 0xFF).toByte() }
        return if (bigEndian) b.reversedArray() else b
    }

    /** The file: header, data, CRC. [corruptCrc] and [truncateBy] damage it on purpose. */
    fun build(
        headerSize: Int = 14,
        corruptCrc: Boolean = false,
        truncateBy: Int = 0,
        magic: String = ".FIT",
    ): ByteArray {
        val data = body.toByteArray()
        val header = ByteArray(headerSize)
        header[0] = headerSize.toByte()
        header[1] = 0x20
        header[2] = 0x4C // profile version, little endian
        header[3] = 0x08
        for (i in 0 until 4) header[4 + i] = ((data.size.toLong() shr (8 * i)) and 0xFF).toByte()
        magic.toByteArray(Charsets.US_ASCII).copyInto(header, 8)
        if (headerSize == 14) {
            val crc = fitCrc(header, 0, 12)
            header[12] = crc.toByte()
            header[13] = (crc shr 8).toByte()
        }
        val out = ByteArrayOutputStream()
        out.write(header)
        out.write(data)
        val whole = out.toByteArray()
        var crc = fitCrc(whole, 0, whole.size)
        if (corruptCrc) crc = crc xor 0x00FF
        out.write(crc and 0xFF)
        out.write((crc shr 8) and 0xFF)
        val file = out.toByteArray()
        return if (truncateBy > 0) file.copyOf(file.size - truncateBy) else file
    }
}

/** The fields of the `record` messages the tests write. */
object FitRecordLayout {
    const val BASE_UINT8 = 0x02
    const val BASE_UINT16 = 0x84
    const val BASE_SINT32 = 0x85
    const val BASE_UINT32 = 0x86
    const val BASE_ENUM = 0x00

    val timestamp = FitField(253, 4, BASE_UINT32)
    val latitude = FitField(0, 4, BASE_SINT32)
    val longitude = FitField(1, 4, BASE_SINT32)
    val altitude = FitField(2, 2, BASE_UINT16)
    val heartRate = FitField(3, 1, BASE_UINT8)
    val cadence = FitField(4, 1, BASE_UINT8)
}

/** Degrees as FIT semicircles (the signed 32-bit integer the format stores). */
fun semicircles(degrees: Double): Long = Math.round(degrees * 2147483648.0 / 180.0)

/** Unix epoch millis as a FIT timestamp (seconds since 1989-12-31). */
fun fitSeconds(unixMs: Long): Long = (unixMs - 631_065_600_000L) / 1000

/**
 * A run of [seconds] seconds going north at 3 m/s from [startMs], with a pause of a minute in the middle (a stopped
 * timer), at a steady [heartRate]. [sport] is the FIT sport of the file, or null for a file that declares none.
 */
fun fitRun(
    startMs: Long,
    seconds: Int = 30,
    sport: Int? = 1,
    heartRate: Int? = 150,
): ByteArray {
    val builder = FitBuilder()
    val t0 = fitSeconds(startMs)
    builder
        .definition(0, 0, listOf(FitField(0, 1, FitRecordLayout.BASE_ENUM)))
        .data(0, builder.u8(4))
    builder
        .definition(
            1,
            21,
            listOf(
                FitRecordLayout.timestamp,
                FitField(0, 1, FitRecordLayout.BASE_ENUM),
                FitField(1, 1, FitRecordLayout.BASE_ENUM),
            ),
        ).data(1, builder.u32(t0), builder.u8(0), builder.u8(0))
    builder.definition(
        3,
        20,
        listOf(
            FitRecordLayout.timestamp,
            FitRecordLayout.latitude,
            FitRecordLayout.longitude,
            FitRecordLayout.altitude,
            FitRecordLayout.heartRate,
        ),
    )
    val metersPerDegree = 111_195.08

    fun record(
        second: Int,
        walked: Int,
    ) = builder.data(
        3,
        builder.u32(t0 + second),
        builder.u32(semicircles(45.0 + walked * 3.0 / metersPerDegree)),
        builder.u32(semicircles(9.19)),
        builder.u16(((120.0 + 500.0) * 5).toInt()),
        builder.u8(heartRate ?: 0xFF),
    )
    val half = seconds / 2
    for (i in 0 until half) record(i, i)
    builder.data(1, builder.u32(t0 + half), builder.u8(0), builder.u8(4)) // timer stop
    builder.data(1, builder.u32(t0 + half + 60), builder.u8(0), builder.u8(0)) // timer start
    for (i in half until seconds) record(i + 60, i)
    if (sport != null) {
        builder
            .definition(2, 18, listOf(FitField(5, 1, FitRecordLayout.BASE_ENUM)))
            .data(2, builder.u8(sport))
    }
    return builder.build()
}
