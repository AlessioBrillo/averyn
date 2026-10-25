package dev.averyn.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** `hr-zones-v1` (docs/metrics/hr-zones.md): five zones by share of the maximum heart rate. */
class HeartRateZonesTest {
    /** One second at each bpm from [from] to [to]. */
    private fun oneSecondEach(
        from: Int,
        to: Int,
    ) = AnalysisHeartRateHistogram(from, List(to - from + 1) { 1_000L })

    @Test
    fun theLimitsAreSharesOfTheMaximumRoundedUp() {
        val zones = heartRateZones(AnalysisHeartRateHistogram(), 190).zones

        // 60 % of 190 = 114, 70 % = 133, 80 % = 152, 90 % = 171.
        assertEquals(listOf(0, 114, 133, 152, 171), zones.map { it.fromBpm })
        assertEquals(listOf(113, 132, 151, 170, null), zones.map { it.toBpm })
        assertEquals(listOf(1, 2, 3, 4, 5), zones.map { it.zone })
    }

    @Test
    fun aLimitThatIsNotAWholeBpmIsRoundedUp() {
        // 60 % of 187 = 112.2 -> 113; 90 % of 187 = 168.3 -> 169.
        val zones = heartRateZones(AnalysisHeartRateHistogram(), 187).zones

        assertEquals(113, zones[1].fromBpm)
        assertEquals(169, zones[4].fromBpm)
    }

    @Test
    fun everyBpmIsInExactlyOneZoneAndTheZonesAddUpToTheHistogram() {
        val histogram = oneSecondEach(from = 40, to = 220)
        val result = heartRateZones(histogram, 190)

        assertEquals(histogram.timeMs.sum(), result.zones.sumOf { it.timeMs })
        // Z1: 40..113 = 74 s, Z2: 114..132 = 19 s, Z3: 133..151 = 19 s, Z4: 152..170 = 19 s, Z5: 171..220 = 50 s.
        assertEquals(listOf(74_000L, 19_000L, 19_000L, 19_000L, 50_000L), result.zones.map { it.timeMs })
    }

    @Test
    fun theBoundariesBelongToTheUpperZone() {
        val result = heartRateZones(AnalysisHeartRateHistogram(113, listOf(1L, 2L, 4L)), 190)

        // 113 is Z1, 114 is Z2 (the lower limit of Z2), 115 is Z2.
        assertEquals(1L, result.zones[0].timeMs)
        assertEquals(6L, result.zones[1].timeMs)
    }

    @Test
    fun aChangedMaximumMovesTheTimeBetweenZonesWithoutRecomputingTheHistogram() {
        val histogram = oneSecondEach(from = 150, to = 150)

        assertEquals(1_000L, heartRateZones(histogram, 190).zones[2].timeMs) // 150 / 190 = 79 %: Z3
        assertEquals(1_000L, heartRateZones(histogram, 160).zones[4].timeMs) // 150 / 160 = 94 %: Z5
    }

    @Test
    fun theResultDeclaresItsVersionAndTheMaximumItUsed() {
        val result = heartRateZones(oneSecondEach(100, 100), 200)

        assertEquals("hr-zones-v1", result.algorithmVersion)
        assertEquals(200, result.maxHrBpm)
        assertNull(result.zones.last().toBpm)
    }

    @Test
    fun aMaximumThatIsNotPositiveIsRefused() {
        assertFailsWith<IllegalArgumentException> { heartRateZones(AnalysisHeartRateHistogram(), 0) }
    }
}
