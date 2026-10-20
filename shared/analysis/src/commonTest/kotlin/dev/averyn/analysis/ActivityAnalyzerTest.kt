package dev.averyn.analysis

import dev.averyn.domain.LocationSample
import dev.averyn.domain.Sport
import dev.averyn.metrics.ActivitySnapshot
import dev.averyn.sensors.HeartRateSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val METERS_PER_DEG_LAT = 111_195.08

private val START_MS = 1_700_000_000_000L

/** One sample at [elapsedMs], [metersNorth] metres north of the start, [altitudeM] metres above the sea. */
private fun sample(
    elapsedMs: Long,
    metersNorth: Double,
    altitudeM: Double? = null,
): LocationSample =
    LocationSample(
        timeMs = START_MS + elapsedMs,
        elapsedRealtimeMs = elapsedMs,
        latitude = metersNorth / METERS_PER_DEG_LAT,
        longitude = 9.0,
        horizontalAccuracyM = 5.0,
        altitudeM = altitudeM,
    )

/** One sample every second, [stepM] metres apart, starting at [firstMetersNorth]. */
private fun track(
    steps: Int,
    stepM: Double = 3.0,
    altitude: ((index: Int) -> Double?)? = null,
    firstElapsedMs: Long = 0,
    firstMetersNorth: Double = 0.0,
): List<LocationSample> =
    List(steps) { index ->
        sample(
            elapsedMs = firstElapsedMs + index * 1_000,
            metersNorth = firstMetersNorth + index * stepM,
            altitudeM = altitude?.invoke(index),
        )
    }

private fun totals(
    distanceM: Double,
    elapsedMs: Long,
    pausedMs: Long = 0,
    movingMs: Long = elapsedMs - pausedMs,
    stationaryMs: Long = 0,
) = ActivitySnapshot(
    distanceM = distanceM,
    elapsedMs = elapsedMs,
    pausedMs = pausedMs,
    movingMs = movingMs,
    stationaryMs = stationaryMs,
    currentSpeedMps = 0.0,
    averageSpeedMps = if (movingMs > 0) distanceM / (movingMs / 1000.0) else 0.0,
    paceSecPerKm = null,
)

class ActivityAnalyzerTest {
    @Test
    fun aStraightRunIsCutInOneKilometerSplitsThatAddUpToTheTrack() {
        val samples = track(steps = 1_000, stepM = 3.0) // 999 legs of 3 m: 2 997 m
        val analysis = analyzeActivity(Sport.RUN, listOf(samples), emptyList(), totals(2_997.0, 999_000))

        assertEquals(3, analysis.splits.size)
        assertEquals(1_000.0, analysis.splitLengthM)
        val first = analysis.splits.first()
        assertEquals(0.0, first.startDistanceM)
        assertEquals(1_000.0, first.endDistanceM, 0.01)
        assertTrue(!first.partial)
        val last = analysis.splits.last()
        assertEquals(2_000.0, last.startDistanceM, 0.01)
        assertEquals(2_997.0, last.endDistanceM, 0.01)
        // The activity ended three metres short of the third kilometer: that split is a partial one.
        assertTrue(analysis.splits[1].endDistanceM - analysis.splits[1].startDistanceM > 999.0)
        assertTrue(!analysis.splits[1].partial)
        assertTrue(last.partial)
        // The splits cover the whole track, without a metre more and without a metre less.
        assertEquals(2_997.0, analysis.splits.sumOf { it.endDistanceM - it.startDistanceM }, 0.01)
        // The boundary falls inside a leg, so the time of a split is interpolated, not a whole number of seconds.
        assertEquals(333_333, first.movingMs)
        assertEquals(3.0, first.avgSpeedMps, 0.001)
    }

    @Test
    fun theLastSplitIsMarkedPartialWhenTheActivityEndsBeforeTheNextBoundary() {
        val samples = track(steps = 350, stepM = 3.0) // 1 047 m
        val analysis = analyzeActivity(Sport.RUN, listOf(samples), emptyList(), totals(1_047.0, 349_000))

        assertEquals(2, analysis.splits.size)
        assertTrue(!analysis.splits[0].partial)
        assertTrue(analysis.splits[1].partial)
        assertEquals(47.0, analysis.splits[1].endDistanceM - analysis.splits[1].startDistanceM, 0.01)
    }

    @Test
    fun aRideIsCutInFiveKilometerSplits() {
        val samples = track(steps = 2_000, stepM = 3.0) // 5 997 m
        val analysis = analyzeActivity(Sport.RIDE, listOf(samples), emptyList(), totals(5_997.0, 1_999_000))

        assertEquals(5_000.0, analysis.splitLengthM)
        assertEquals(2, analysis.splits.size)
        assertTrue(analysis.splits[1].partial)
    }

    @Test
    fun aPauseIsNotCrossedByTheTrackAndTheDistanceDoesNotJumpOverIt() {
        val before = track(steps = 3, stepM = 100.0) // 0, 100, 200 m
        val after = track(steps = 3, stepM = 100.0, firstElapsedMs = 60_000, firstMetersNorth = 200.0)
        val analysis =
            analyzeActivity(
                Sport.RUN,
                listOf(before, after),
                emptyList(),
                totals(400.0, 62_000, pausedMs = 58_000),
            )

        // The distance of the second segment starts where the first one ended: the pause is not distance.
        val expected = listOf(0.0, 100.0, 200.0, 200.0, 300.0, 400.0)
        expected.forEachIndexed { index, value ->
            assertEquals(value, analysis.series.distanceM[index], 0.001)
        }
        assertEquals(listOf(0, 0, 0, 1, 1, 1), analysis.series.segment)
        assertEquals(400.0, analysis.splits.single().endDistanceM, 0.01)
    }

    @Test
    fun theMovingTimeOfASplitExcludesTheStationaryLegs() {
        // Three moving seconds, three stationary ones, three moving ones: 9 m in 6 s.
        val samples =
            listOf(
                sample(0, 0.0, 100.0),
                sample(1_000, 3.0, 100.0),
                sample(2_000, 6.0, 100.0),
                sample(3_000, 6.0, 100.0),
                sample(4_000, 6.0, 100.0),
                sample(5_000, 6.0, 100.0),
                sample(6_000, 9.0, 100.0),
            )
        val analysis = analyzeActivity(Sport.RUN, listOf(samples), emptyList(), totals(9.0, 6_000))

        val split = analysis.splits.single()
        assertEquals(9.0, split.endDistanceM, 0.01)
        // Three moving legs of one second each: the three stationary seconds are not moving time.
        assertEquals(3_000, split.movingMs)
        assertEquals(6_000, split.elapsedMs)
    }

    @Test
    fun aSteadyClimbIsCountedAsSuch() {
        val samples = track(steps = 100, stepM = 3.0, altitude = { it.toDouble() })
        val analysis = analyzeActivity(Sport.RUN, listOf(samples), emptyList(), totals(300.0, 99_000))

        val elevation = assertNotNull(analysis.elevation)
        assertEquals(ELEVATION_SOURCE_RECORDED, elevation.source)
        val climb = elevation.maxM - elevation.minM
        // The climb is counted up to the 3 m threshold: the last step of it stays uncounted.
        assertTrue(elevation.gainM > climb - 3.2, "gain was ${elevation.gainM} of a $climb m climb")
        assertTrue(elevation.gainM <= climb + 0.001, "gain was ${elevation.gainM} of a $climb m climb")
        assertEquals(0.0, elevation.lossM, 0.001)
        assertEquals(100.0, elevation.coveragePercent, 0.001)
    }

    @Test
    fun anAltitudeThatOnlyJittersIsNotAMountain() {
        val samples = track(steps = 100, stepM = 3.0, altitude = { if (it % 2 == 0) 100.0 else 101.0 })
        val analysis = analyzeActivity(Sport.RUN, listOf(samples), emptyList(), totals(300.0, 99_000))

        val elevation = assertNotNull(analysis.elevation)
        assertEquals(0.0, elevation.gainM, 0.001)
        assertEquals(0.0, elevation.lossM, 0.001)
    }

    @Test
    fun anActivityWithoutAltitudeHasNoElevation() {
        val samples = track(steps = 100, stepM = 3.0)
        val analysis = analyzeActivity(Sport.RUN, listOf(samples), emptyList(), totals(300.0, 99_000))

        assertNull(analysis.elevation)
        // The splits exist all the same: the elevation was never measured, the distance was.
        assertEquals(1, analysis.splits.size)
        assertNull(analysis.splits.single().elevationGainM)
        assertNull(analysis.splits.single().elevationLossM)
    }

    @Test
    fun theHeartRateIsMeasuredOverTheRecordingTimeThePausesExcluded() {
        val before = track(steps = 60, stepM = 3.0) // 60 s of recording
        val after = track(steps = 60, stepM = 3.0, firstElapsedMs = 120_000, firstMetersNorth = 177.0)
        val heartRate =
            (0 until 120).map { index ->
                HeartRateSample(
                    timeMs = START_MS + index * 1_000L,
                    elapsedRealtimeMs = index * 1_000L,
                    bpm = 130,
                )
            }
        val analysis =
            analyzeActivity(
                Sport.RUN,
                listOf(before, after),
                heartRate,
                totals(360.0, 120_000, pausedMs = 60_000),
            )

        val heart = assertNotNull(analysis.heartRate)
        // 120 samples over 120 s of recording: the paused minute is not in the denominator.
        assertEquals(100.0, heart.coveragePercent, 0.001)
        assertEquals(0, heart.dropouts)
        assertEquals(130, heart.avgBpm)
        assertEquals(130, heart.minBpm)
        assertEquals(130, heart.maxBpm)
    }

    @Test
    fun anImplausibleHeartRateIsCountedAsAnArtifactAndNotUsed() {
        val heartRate =
            listOf(
                HeartRateSample(START_MS, 0, 130),
                HeartRateSample(START_MS + 1_000, 1_000, 0),
                HeartRateSample(START_MS + 2_000, 2_000, 140),
            )
        val analysis =
            analyzeActivity(
                Sport.RUN,
                listOf(track(steps = 10, stepM = 3.0)),
                heartRate,
                totals(27.0, 9_000),
            )

        val heart = assertNotNull(analysis.heartRate)
        assertEquals(1, heart.artifacts)
        assertEquals(135, heart.avgBpm)
        // Two usable samples over nine seconds of recording.
        assertEquals(22.2, heart.coveragePercent, 0.1)
    }

    @Test
    fun anActivityWithoutHeartRateHasNoHeartRate() {
        val analysis =
            analyzeActivity(
                Sport.RUN,
                listOf(track(steps = 10, stepM = 3.0)),
                emptyList(),
                totals(27.0, 9_000),
            )

        assertNull(analysis.heartRate)
        assertTrue(
            analysis.series.heartRate.bpm
                .isEmpty(),
        )
    }

    @Test
    fun theSeriesIsBoundedAndKeepsTheFirstAndTheLastPoint() {
        val samples = track(steps = 5_000, stepM = 3.0)
        val analysis = analyzeActivity(Sport.RUN, listOf(samples), emptyList(), totals(14_997.0, 4_999_000))

        assertEquals(2_000, analysis.series.distanceM.size)
        assertEquals(2_000, analysis.series.elevationM.size)
        assertEquals(2_000, analysis.series.elapsedMs.size)
        assertEquals(2_000, analysis.series.segment.size)
        assertEquals(0.0, analysis.series.distanceM.first())
        assertEquals(14_997.0, analysis.series.distanceM.last(), 0.01)
    }

    @Test
    fun theHeartRateSeriesStaysBoundedWhenTheSensorDropsOut() {
        // 600 stretches of ten samples, each one after a silence: more stretches than the bound has points.
        val heartRate =
            (0 until 600).flatMap { group ->
                (0 until 10).map { index ->
                    HeartRateSample(
                        timeMs = START_MS + group * 20_000L + index * 1_000L,
                        elapsedRealtimeMs = group * 20_000L + index * 1_000L,
                        bpm = 130,
                    )
                }
            }
        val analysis =
            analyzeActivity(
                Sport.RUN,
                listOf(track(steps = 10, stepM = 3.0)),
                heartRate,
                totals(30.0, 9_000),
            )

        val series = analysis.series.heartRate
        assertTrue(series.bpm.size <= 1000, "the series had ${series.bpm.size} points")
        // The first and the last sample of the activity are still there.
        assertEquals(0L, series.elapsedMs.first())
        assertEquals(599 * 20_000L + 9_000L, series.elapsedMs.last())
    }

    @Test
    fun anAltitudeThatWasNotMeasuredStaysAHole() {
        // Ten samples, only three of them with an altitude.
        val samples =
            track(steps = 10, stepM = 3.0, altitude = { index -> if (index in listOf(2, 5, 8)) 100.0 else null })
        val analysis = analyzeActivity(Sport.RUN, listOf(samples), emptyList(), totals(30.0, 9_000))

        val elevation = assertNotNull(analysis.elevation)
        // The coverage is the share of the samples that carried an altitude, not of the ones that inherited one.
        assertEquals(30.0, elevation.coveragePercent, 0.001)
        // The holes are holes: the profile does not invent an altitude between two measured ones.
        assertEquals(10, analysis.series.elevationM.size)
        assertEquals(3, analysis.series.elevationM.count { it != null })
        assertNull(analysis.series.elevationM[0])
        assertNotNull(analysis.series.elevationM[2])
        assertNull(analysis.series.elevationM[4])
    }

    @Test
    fun theAnalysisDeclaresTheVersionOfEveryAlgorithm() {
        val analysis =
            analyzeActivity(
                Sport.RUN,
                listOf(track(steps = 10, stepM = 3.0)),
                emptyList(),
                totals(27.0, 9_000),
            )

        assertEquals(SPLITS_ALGORITHM_VERSION, analysis.algorithmVersions.splits)
        assertEquals(ELEVATION_ALGORITHM_VERSION, analysis.algorithmVersions.elevation)
        assertEquals(SERIES_ALGORITHM_VERSION, analysis.algorithmVersions.series)
        assertEquals("hr-v2", analysis.algorithmVersions.heartRate)
        assertEquals(ANALYSIS_SCHEMA_VERSION, analysis.schemaVersion)
    }
}
