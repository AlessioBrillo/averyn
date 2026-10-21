package dev.averyn.analysis

import dev.averyn.domain.LocationSample
import dev.averyn.domain.Sport
import dev.averyn.metrics.ActivitySnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val METERS_PER_DEG_LAT = 111_195.08

private val START_MS = 1_700_000_000_000L

/** One sample at [elapsedMs], [metersNorth] metres north of the start. */
private fun sample(
    elapsedMs: Long,
    metersNorth: Double,
): LocationSample =
    LocationSample(
        timeMs = START_MS + elapsedMs,
        elapsedRealtimeMs = elapsedMs,
        latitude = metersNorth / METERS_PER_DEG_LAT,
        longitude = 9.0,
        horizontalAccuracyM = 5.0,
    )

/** A sample every [stepMs] milliseconds, [stepM] metres further north every time. */
private fun track(
    steps: Int,
    stepM: Double = 3.0,
    stepMs: Long = 1_000,
    firstMetersNorth: Double = 0.0,
    firstElapsedMs: Long = 0,
): List<LocationSample> =
    List(steps) { index ->
        sample(
            elapsedMs = firstElapsedMs + index * stepMs,
            metersNorth = firstMetersNorth + index * stepM,
        )
    }

/**
 * One recording interval of 400 samples a second apart, 3 m each, with a silence of [silenceMs] in the middle:
 * the signal was lost for a moment. The athlete covered [distanceDuringSilenceM] in the meantime, which the
 * interpolation of the track counts as distance: with a long silence that distance is distance nobody ran.
 */
private fun trackWithSilence(
    silenceMs: Long,
    distanceDuringSilenceM: Double,
): List<LocationSample> {
    val first = track(steps = 201, stepM = 3.0) // 0 .. 600 m, 0 .. 200 s
    val second =
        track(
            steps = 200,
            stepM = 3.0,
            firstMetersNorth = 600.0 + distanceDuringSilenceM,
            firstElapsedMs = 200_000 + silenceMs,
        )
    return first + second
}

/** The totals of an activity: the analysis does not need them to be exact for the best efforts. */
private fun totals(
    distanceM: Double,
    elapsedMs: Long,
): ActivitySnapshot =
    ActivitySnapshot(
        distanceM = distanceM,
        elapsedMs = elapsedMs,
        pausedMs = 0,
        movingMs = elapsedMs,
        stationaryMs = 0,
        currentSpeedMps = 0.0,
        averageSpeedMps = if (elapsedMs > 0) distanceM / (elapsedMs / 1000.0) else 0.0,
        paceSecPerKm = null,
    )

class BestEffortsTest {
    @Test
    fun theTargetsAreTheStandardDistancesOfTheSport() {
        assertEquals(
            listOf(1_000.0, 5_000.0, 10_000.0, 21_097.5, 42_195.0),
            bestEffortDistancesM(Sport.RUN),
        )
        assertEquals(
            listOf(5_000.0, 10_000.0, 20_000.0, 40_000.0, 50_000.0, 100_000.0),
            bestEffortDistancesM(Sport.RIDE),
        )
        assertEquals(listOf(1_000.0, 5_000.0, 10_000.0), bestEffortDistancesM(Sport.WALK))
        assertEquals(listOf(1_000.0, 5_000.0, 10_000.0), bestEffortDistancesM(Sport.HIKE))
    }

    @Test
    fun aStraightRunHasABestEffortForEveryTargetItReaches() {
        val samples = track(steps = 3_400) // 3 m every second: 10 197 m
        val efforts = bestEfforts(Sport.RUN, listOf(samples))

        // Only the targets the activity reaches: the half marathon and the marathon are out of reach.
        assertEquals(listOf(1_000.0, 5_000.0, 10_000.0), efforts.map { it.distanceM })
        // A kilometre at 3 m/s: the boundary falls inside a leg, so the time is interpolated.
        assertEquals(333_333, efforts[0].durationMs)
        assertEquals(1_666_667, efforts[1].durationMs)
        assertEquals(3_333_333, efforts[2].durationMs)
        // The first kilometre starts where the activity starts.
        assertEquals(0.0, efforts[0].startDistanceM, 0.1)
        assertEquals(1_000.0, efforts[0].endDistanceM, 0.1)
        assertEquals(0L, efforts[0].startElapsedMs)
        // The speed is the distance over the moving time.
        assertEquals(3.0, efforts[0].avgSpeedMps, 0.01)
        assertEquals(333.33, efforts[0].paceSecPerKm!!, 0.1)
    }

    @Test
    fun theBestEffortIsTheFastestWindowAndNotTheFirstSplit() {
        // A slow kilometre, a slow kilometre and a fast one.
        val samples =
            listOf(
                sample(0, 0.0),
                sample(360_000, 1_000.0),
                sample(720_000, 2_000.0),
                sample(900_000, 3_000.0),
            )
        val efforts = bestEfforts(Sport.RUN, listOf(samples))

        val oneKm = efforts.single { it.distanceM == 1_000.0 }
        // The fast kilometre is the third one, not the first.
        assertEquals(180_000, oneKm.durationMs)
        assertEquals(2_000.0, oneKm.startDistanceM, 0.1)
        assertEquals(3_000.0, oneKm.endDistanceM, 0.1)
        // The activity is 3 km long: the half marathon and the marathon are out of reach.
        assertEquals(listOf(1_000.0), efforts.map { it.distanceM })
    }

    @Test
    fun theFastestWindowCanStartInTheMiddleOfALeg() {
        // 10 m in 15 s, then 10 m in 1 s: the fastest 15 m start in the middle of the slow leg.
        val samples =
            listOf(
                sample(0, 0.0),
                sample(15_000, 10.0),
                sample(16_000, 20.0),
            )
        val effort = fastestWindowOf(Sport.RUN, listOf(samples), 15.0)

        assertNotNull(effort)
        // A window that starts on a sample would take 15.5 s: this one takes 8.5 s, because it starts in the
        // middle of the slow leg and crosses the fast one entirely.
        assertEquals(8_500, effort.durationMs)
        assertEquals(5.0, effort.startDistanceM, 0.01)
        assertEquals(20.0, effort.endDistanceM, 0.01)
        assertEquals(7_500, effort.startElapsedMs)
        assertEquals(16_000, effort.endElapsedMs)
    }

    @Test
    fun aPauseBreaksTheBestEffort() {
        val before = track(steps = 3, stepM = 200.0, stepMs = 20_000) // 0, 200, 400 m
        val after = track(steps = 3, stepM = 200.0, stepMs = 20_000, firstMetersNorth = 400.0, firstElapsedMs = 300_000)
        val efforts = bestEfforts(Sport.RUN, listOf(before, after))

        // Neither interval is a kilometre long: an effort never crosses a pause.
        assertTrue(efforts.isEmpty(), "the best efforts were ${efforts.map { it.distanceM }}")
    }

    @Test
    fun aSignalGapBreaksTheBestEffort() {
        // 200 samples a second apart, then a silence of 60 s: the athlete was inside a tunnel and the
        // straight line ahead is 24 m, which the interpolation counts as distance nobody ran.
        val samples = trackWithSilence(silenceMs = 60_000, distanceDuringSilenceM = 24.0)
        val efforts = bestEfforts(Sport.RUN, listOf(samples))

        // The first stretch is 600 m and the second 600 m: no kilometre without inventing what was not measured.
        assertTrue(efforts.isEmpty(), "the best efforts were ${efforts.map { it.distanceM }}")
    }

    @Test
    fun aShortGapIsCrossed() {
        // The same track, with a silence of 5 s: shorter than the threshold of the metric.
        val samples = trackWithSilence(silenceMs = 5_000, distanceDuringSilenceM = 15.0)
        val efforts = bestEfforts(Sport.RUN, listOf(samples))

        val oneKm = efforts.single { it.distanceM == 1_000.0 }
        // The run is continuous: the effort crosses the silence.
        assertTrue(oneKm.durationMs > 300_000, "the effort was ${oneKm.durationMs} ms")
    }

    @Test
    fun theStationaryLegsAreNotMovingTime() {
        // 500 m in 50 s, then 4 m of drift in 10 s (the athlete stands still), then the rest.
        val samples =
            listOf(
                sample(0, 0.0),
                sample(50_000, 500.0),
                sample(60_000, 504.0),
                sample(160_000, 1_000.0),
            )
        val efforts = bestEfforts(Sport.RUN, listOf(samples))

        val oneKm = efforts.single { it.distanceM == 1_000.0 }
        // The 10 s of drift are not moving time: they are in the elapsed time and not in the moving one.
        assertEquals(150_000, oneKm.durationMs)
        assertEquals(160_000, oneKm.elapsedMs)
    }

    @Test
    fun anImplausibleWindowIsSkipped() {
        // A kilometre in one second: a GPS jump, not a runner. There is nothing else on the track.
        val samples = listOf(sample(0, 0.0), sample(1_000, 1_000.0))
        val efforts = bestEfforts(Sport.RUN, listOf(samples))

        // No athlete covers a kilometre in a second: the window is not a record, and there is no other one.
        assertTrue(efforts.isEmpty(), "the best efforts were ${efforts.map { it.distanceM }}")
    }

    @Test
    fun aJumpDoesNotBecomeTheRecordOfTheActivity() {
        // A kilometre in one second, then a real 8 m/s.
        val samples =
            listOf(
                sample(0, 0.0),
                sample(1_000, 1_000.0),
                sample(13_000, 1_100.0),
                sample(25_000, 1_200.0),
                sample(37_000, 1_300.0),
                sample(49_000, 1_400.0),
                sample(61_000, 1_500.0),
                sample(73_000, 1_600.0),
                sample(85_000, 1_700.0),
                sample(97_000, 1_800.0),
                sample(109_000, 1_900.0),
                sample(121_000, 2_000.0),
            )
        val efforts = bestEfforts(Sport.RUN, listOf(samples))

        val oneKm = efforts.single { it.distanceM == 1_000.0 }
        // The one-second kilometre is not the record: the record is a window that moves.
        assertTrue(oneKm.durationMs > 10_000, "the record was ${oneKm.durationMs} ms")
    }

    @Test
    fun anActivityShorterThanTheTargetHasNoBestEffort() {
        val efforts = bestEfforts(Sport.RUN, listOf(track(steps = 100))) // 297 m

        assertTrue(efforts.isEmpty())
    }

    @Test
    fun aRideIsMeasuredInFiveKilometres() {
        val samples = track(steps = 2_000) // 5 997 m at 3 m/s
        val efforts = bestEfforts(Sport.RIDE, listOf(samples))

        // A ride has no kilometre split of records: the first target is five kilometres.
        assertEquals(listOf(5_000.0), efforts.map { it.distanceM })
        assertEquals(1_666_667, efforts.single().durationMs)
    }

    @Test
    fun theAnalysisCarriesTheBestEffortsAndDeclaresTheirVersion() {
        val samples = track(steps = 3_400)
        val analysis = analyzeActivity(Sport.RUN, listOf(samples), emptyList(), totals(10_197.0, 3_399_000))

        assertEquals(BEST_EFFORTS_ALGORITHM_VERSION, analysis.algorithmVersions.bestEfforts)
        assertEquals(3, analysis.bestEfforts.size)
        assertEquals(1_000.0, analysis.bestEfforts.first().distanceM)
        // The analysis is the same document as before, with one more thing in it.
        assertEquals(2, ANALYSIS_SCHEMA_VERSION)
    }

    @Test
    fun anActivityWithNoTrackHasNoBestEffort() {
        val analysis = analyzeActivity(Sport.RUN, listOf(emptyList()), emptyList(), totals(0.0, 0))

        assertTrue(analysis.bestEfforts.isEmpty())
        assertNull(analysis.elevation)
    }
}
