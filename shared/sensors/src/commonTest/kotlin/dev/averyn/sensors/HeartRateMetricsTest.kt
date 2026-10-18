package dev.averyn.sensors

import kotlin.test.Test
import kotlin.test.assertEquals

class HeartRateMetricsTest {
    @Test
    fun emptyActivityReportsZeroesAndAllExpectedIntervalsAsDropouts() {
        val metrics = HeartRateMetrics()
        val snapshot = metrics.snapshot(300_000) // 5 minutes, no samples

        assertEquals(0, snapshot.avgHrBpm)
        assertEquals(0, snapshot.maxHrBpm)
        assertEquals(0, snapshot.minHrBpm)
        assertEquals(0.0, snapshot.coveragePercent)
        assertEquals(300, snapshot.dropouts) // 300 s at 1 s interval
        assertEquals(0, snapshot.artifacts)
    }

    @Test
    fun computesAvgMaxMinCoverageAndDropouts() {
        val metrics = HeartRateMetrics()
        metrics.onSample(130)
        metrics.onSample(140)
        metrics.onSample(150)
        // 3 samples over 10 s: coverage 30%, dropouts 7
        val snapshot = metrics.snapshot(10_000)

        assertEquals(140, snapshot.avgHrBpm)
        assertEquals(150, snapshot.maxHrBpm)
        assertEquals(130, snapshot.minHrBpm)
        assertEquals(30.0, snapshot.coveragePercent)
        assertEquals(7, snapshot.dropouts)
        assertEquals(0, snapshot.artifacts)
    }

    @Test
    fun outOfRangeBpmIsCountedAsArtifactExcludedFromAvgMaxMin() {
        val metrics = HeartRateMetrics()
        metrics.onSample(130)
        metrics.onSample(0) // invalid: no reading
        metrics.onSample(300) // invalid: above max
        metrics.onSample(140)
        val snapshot = metrics.snapshot(10_000)

        assertEquals(135, snapshot.avgHrBpm) // (130 + 140) / 2
        assertEquals(140, snapshot.maxHrBpm)
        assertEquals(130, snapshot.minHrBpm)
        assertEquals(2, snapshot.artifacts)
        assertEquals(20.0, snapshot.coveragePercent) // 2 valid of 10 expected
        assertEquals(8, snapshot.dropouts)
    }

    @Test
    fun coverageIsCappedAt100Percent() {
        val metrics = HeartRateMetrics()
        repeat(200) { metrics.onSample(130) } // more samples than expected intervals
        val snapshot = metrics.snapshot(10_000) // 10 expected

        assertEquals(100.0, snapshot.coveragePercent)
        assertEquals(0, snapshot.dropouts)
    }
}
