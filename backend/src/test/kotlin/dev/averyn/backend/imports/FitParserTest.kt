package dev.averyn.backend.imports

import dev.averyn.domain.Sport
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The FIT reader (TDD-0013): what it takes from a file, what it counts and what it refuses. */
class FitParserTest {
    private val parser = FitParser()

    /** 2026-10-26T08:00:00Z. */
    private val startMs = 1_792_999_200_000L

    private fun parse(
        bytes: ByteArray,
        fit: FitParser = parser,
    ): ImportParseResult = fit.parse(ByteArrayInputStream(bytes))

    private fun invalid(result: ImportParseResult): ImportParseResult.Invalid =
        assertIs<ImportParseResult.Invalid>(result)

    private fun track(result: ImportParseResult): ImportedTrack = assertIs<ImportParseResult.Parsed>(result).track

    /** file_id: type 4 (activity). */
    private fun FitBuilder.fileId(type: Int = 4): FitBuilder =
        definition(0, 0, listOf(FitField(0, 1, FitRecordLayout.BASE_ENUM)))
            .data(0, u8(type))

    /** event: [event] and [eventType] at [seconds]. */
    private fun FitBuilder.event(
        seconds: Long,
        event: Int = 0,
        eventType: Int,
    ): FitBuilder =
        definition(
            1,
            21,
            listOf(
                FitRecordLayout.timestamp,
                FitField(0, 1, FitRecordLayout.BASE_ENUM),
                FitField(1, 1, FitRecordLayout.BASE_ENUM),
            ),
        ).data(1, u32(seconds), u8(event), u8(eventType))

    private fun FitBuilder.session(sport: Int): FitBuilder =
        definition(2, 18, listOf(FitField(5, 1, FitRecordLayout.BASE_ENUM))).data(2, u8(sport))

    /** The layout of the records the tests write: time, position, altitude, heart rate, cadence. */
    private fun FitBuilder.recordLayout(): FitBuilder =
        definition(
            3,
            20,
            listOf(
                FitRecordLayout.timestamp,
                FitRecordLayout.latitude,
                FitRecordLayout.longitude,
                FitRecordLayout.altitude,
                FitRecordLayout.heartRate,
                FitRecordLayout.cadence,
            ),
        )

    /** One record: a point [index] seconds after the start, 10 m north of the last one. */
    private fun FitBuilder.record(
        seconds: Long,
        latitude: Double,
        longitude: Double = 9.19,
        altitudeM: Double? = 120.0,
        heartRate: Int? = 140,
        cadence: Int? = 80,
    ): FitBuilder =
        data(
            3,
            u32(seconds),
            u32(semicircles(latitude)),
            u32(semicircles(longitude)),
            u16(if (altitudeM == null) 0xFFFF else ((altitudeM + 500.0) * 5.0).toInt()),
            u8(heartRate ?: 0xFF),
            u8(cadence ?: 0xFF),
        )

    private fun simpleActivity(
        sport: Int = 1,
        builder: FitBuilder = FitBuilder(),
    ): FitBuilder {
        val t0 = fitSeconds(startMs)
        builder
            .fileId()
            .event(t0, eventType = 0)
            .recordLayout()
        for (i in 0 until 5) builder.record(t0 + i, 45.0 + i * 0.0001)
        builder.event(t0 + 5, eventType = 4).session(sport)
        return builder
    }

    @Test
    fun theCrcIsTheOneOfTheFormat() {
        // CRC-16/ARC of the ASCII digits 1 to 9: the published check value of the algorithm the format uses.
        val digits = "123456789".toByteArray()

        assertEquals(0xBB3D, fitCrc(digits, 0, digits.size))
    }

    @Test
    fun anActivityGivesItsPointsItsTimeItsAltitudeItsHeartRateAndItsSport() {
        val result = track(parse(simpleActivity().build()))

        assertEquals(1, result.segments.size)
        assertEquals(5, result.pointCount)
        val first = result.segments.single().first()
        assertEquals(startMs, first.timeMs)
        assertEquals(45.0, first.latitude, 1e-6)
        assertEquals(9.19, first.longitude, 1e-6)
        assertEquals(120.0, checkNotNull(first.altitudeM), 0.2)
        assertEquals(startMs + 4_000, result.lastTimeMs)
        assertEquals(5, result.heartRates.size)
        assertEquals(140, result.heartRates.first().bpm)
        assertEquals(Sport.RUN, result.sport)
        // The cadence is not imported: the report says it was in the file.
        assertEquals(5, result.ignoredExtensions["cadence"])
    }

    @Test
    fun theSportOfTheFileIsMappedAndAnUnknownOneIsNull() {
        assertEquals(Sport.RIDE, track(parse(simpleActivity(sport = 2).build())).sport)
        assertEquals(Sport.WALK, track(parse(simpleActivity(sport = 11).build())).sport)
        assertEquals(Sport.HIKE, track(parse(simpleActivity(sport = 17).build())).sport)
        assertNull(track(parse(simpleActivity(sport = 4).build())).sport) // fitness equipment
    }

    @Test
    fun aStoppedTimerIsAPauseAndWhatIsRecordedWhileStoppedIsCounted() {
        val t0 = fitSeconds(startMs)
        val file =
            FitBuilder()
                .fileId()
                .event(t0, eventType = 0)
                .recordLayout()
                .record(t0, 45.0)
                .record(t0 + 1, 45.0001)
                .event(t0 + 2, eventType = 4)
                // The device still wrote a record while the timer was stopped: it is not part of the track.
                .record(t0 + 3, 45.5)
                .event(t0 + 60, eventType = 0)
                .record(t0 + 61, 45.0002)
                .record(t0 + 62, 45.0003)
                .event(t0 + 63, eventType = 4)
                .build()

        val result = track(parse(file))

        assertEquals(2, result.segments.size)
        assertEquals(listOf(2, 2), result.segments.map { it.size })
        assertEquals(1, result.ignoredExtensions["records_while_timer_stopped"])
        // The second segment starts after the first one ends: the gap is the pause.
        assertTrue(result.segments[1].first().timeMs > result.segments[0].last().timeMs)
    }

    @Test
    fun aRecordWithoutAPositionKeepsItsHeartRateAndIsCounted() {
        val t0 = fitSeconds(startMs)
        val file =
            FitBuilder()
                .fileId()
                .recordLayout()
                .record(t0, 45.0)
                // GPS lost for a second: the position is the invalid value, the heart rate is still a reading.
                .data(
                    3,
                    FitBuilder().u32(t0 + 1),
                    FitBuilder().u32(0x7FFFFFFFL),
                    FitBuilder().u32(0x7FFFFFFFL),
                    FitBuilder().u16(0xFFFF),
                    FitBuilder().u8(150),
                    FitBuilder().u8(0xFF),
                ).record(t0 + 2, 45.0002)
                .build()

        val result = track(parse(file))

        assertEquals(2, result.pointCount)
        assertEquals(listOf(140, 150, 140), result.heartRates.map { it.bpm })
        assertEquals(1, result.ignoredExtensions["records_without_position"])
    }

    @Test
    fun aCompressedTimestampHeaderRebuildsTheTime() {
        val t0 = fitSeconds(startMs) // 0x...: the low five bits are what the compressed header carries
        val builder = FitBuilder().fileId().recordLayout().record(t0, 45.0)
        // The next record, 3 seconds later, written with a compressed header: only the offset is in the header.
        // A compressed header carries only two bits of local type (0 to 3): this one uses 2.
        builder.definition(
            2,
            20,
            listOf(
                FitRecordLayout.latitude,
                FitRecordLayout.longitude,
                FitRecordLayout.heartRate,
            ),
        )
        builder.compressedData(
            2,
            ((t0 + 3) and 0x1F).toInt(),
            builder.u32(semicircles(45.0003)),
            builder.u32(semicircles(9.19)),
            builder.u8(141),
        )
        // And one 27 seconds after that one: the offset is relative to the last time, within 31 seconds.
        builder.compressedData(
            2,
            ((t0 + 30) and 0x1F).toInt(),
            builder.u32(semicircles(45.0004)),
            builder.u32(semicircles(9.19)),
            builder.u8(142),
        )

        val result = track(parse(builder.build()))

        assertEquals(listOf(startMs, startMs + 3_000, startMs + 30_000), result.segments.single().map { it.timeMs })
    }

    @Test
    fun aBigEndianFileIsRead() {
        val big = FitBuilder(bigEndian = true)
        val t0 = fitSeconds(startMs)
        big.fileId().recordLayout()
        for (i in 0 until 3) big.record(t0 + i, 45.0 + i * 0.0001)

        val result = track(parse(big.build()))

        assertEquals(3, result.pointCount)
        assertEquals(startMs + 2_000, result.lastTimeMs)
        assertEquals(
            45.0002,
            result.segments
                .single()
                .last()
                .latitude,
            1e-6,
        )
    }

    @Test
    fun developerDataIsSkippedByItsSizeAndCounted() {
        val t0 = fitSeconds(startMs)
        val builder = FitBuilder().fileId()
        builder.definition(
            3,
            20,
            listOf(FitRecordLayout.timestamp, FitRecordLayout.latitude, FitRecordLayout.longitude),
            developerFields = listOf(2, 1),
        )
        builder.data(
            3,
            builder.u32(t0),
            builder.u32(semicircles(45.0)),
            builder.u32(semicircles(9.19)),
            byteArrayOf(1, 2, 3), // the developer fields: 2 + 1 bytes
        )
        builder.data(
            3,
            builder.u32(t0 + 1),
            builder.u32(semicircles(45.0001)),
            builder.u32(semicircles(9.19)),
            byteArrayOf(4, 5, 6),
        )

        val result = track(parse(builder.build()))

        assertEquals(2, result.pointCount)
        assertEquals(2, result.ignoredExtensions["developer_data_fields"])
    }

    @Test
    fun aMessageItDoesNotKnowIsSkippedByItsSize() {
        val t0 = fitSeconds(startMs)
        val builder = FitBuilder().fileId()
        builder.definition(5, 999, listOf(FitField(0, 8, 0x0D), FitField(1, 2, FitRecordLayout.BASE_UINT16)))
        builder.data(5, ByteArray(8) { 7 }, builder.u16(5))
        builder.recordLayout().record(t0, 45.0).record(t0 + 1, 45.0001)

        assertEquals(2, track(parse(builder.build())).pointCount)
    }

    @Test
    fun aHeartRateOutsideTheTrackIsCountedAndNotStored() {
        val t0 = fitSeconds(startMs)
        val builder = FitBuilder().fileId().recordLayout()
        // A reading from the strap before the GPS found its first fix.
        builder.data(
            3,
            builder.u32(t0 - 30),
            builder.u32(0x7FFFFFFFL),
            builder.u32(0x7FFFFFFFL),
            builder.u16(0xFFFF),
            builder.u8(100),
            builder.u8(0xFF),
        )
        builder.record(t0, 45.0).record(t0 + 1, 45.0001)

        val result = track(parse(builder.build()))

        assertEquals(2, result.heartRates.size)
        assertEquals(1, result.ignoredExtensions["heart_rate_outside_track"])
    }

    @Test
    fun aDamagedFileIsRefusedWithAReason() {
        assertEquals("fit_crc", invalid(parse(simpleActivity().build(corruptCrc = true))).code)
        assertEquals("fit_truncated", invalid(parse(simpleActivity().build(truncateBy = 20))).code)
        assertEquals("not_fit", invalid(parse(simpleActivity().build(magic = "NOPE"))).code)
        assertEquals("not_fit", invalid(parse("<gpx/>".toByteArray())).code)
        assertEquals("not_fit", invalid(parse(ByteArray(0))).code)
    }

    @Test
    fun aCourseOrAWorkoutIsNotAnActivity() {
        val t0 = fitSeconds(startMs)
        val file =
            FitBuilder()
                .fileId(type = 6)
                .recordLayout()
                .record(t0, 45.0)
                .build() // 6 = course

        assertEquals("fit_not_activity", invalid(parse(file)).code)
    }

    @Test
    fun aFileWithoutPositionsHasNoTrack() {
        val t0 = fitSeconds(startMs)
        val builder = FitBuilder().fileId().recordLayout()
        builder.data(
            3,
            builder.u32(t0),
            builder.u32(0x7FFFFFFFL),
            builder.u32(0x7FFFFFFFL),
            builder.u16(0xFFFF),
            builder.u8(140),
            builder.u8(0xFF),
        )

        assertEquals("no_track", invalid(parse(builder.build())).code)
    }

    @Test
    fun aRecordWithoutARealTimeIsRefused() {
        // A timestamp below 0x10000000 counts from the start of the device, it is not a date.
        val file =
            FitBuilder()
                .fileId()
                .recordLayout()
                .record(1_000, 45.0)
                .build()

        assertEquals("invalid_time", invalid(parse(file)).code)
    }

    @Test
    fun aCoordinateOutOfRangeIsRefusedWithoutSayingIt() {
        val t0 = fitSeconds(startMs)
        val result =
            invalid(
                parse(
                    FitBuilder()
                        .fileId()
                        .recordLayout()
                        .record(t0, 120.0)
                        .build(),
                ),
            )

        assertEquals("invalid_coordinate", result.code)
        assertTrue(!result.message.contains("120"), result.message)
    }

    @Test
    fun aFileWithTooManyPointsIsRefused() {
        val small = FitParser(maxPoints = 3)

        assertEquals("too_many_points", invalid(parse(simpleActivity().build(), small)).code)
    }

    @Test
    fun aHeaderOfTwelveBytesIsRead() {
        val result = track(parse(simpleActivity().build(headerSize = 12)))

        assertEquals(5, result.pointCount)
    }
}
