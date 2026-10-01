package dev.averyn.metrics

import dev.averyn.domain.LocationSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ActivityMetricsTest {
    private fun sampleAt(
        elapsedMs: Long,
        lat: Double,
        lon: Double,
    ) = LocationSample(
        timeMs = elapsedMs,
        elapsedRealtimeMs = elapsedMs,
        latitude = lat,
        longitude = lon,
        horizontalAccuracyM = 5.0,
    )

    @Test
    fun straightLineTenMetersPerSecond() {
        val metrics = ActivityMetrics()
        // 1 degree of latitude ~= 111_195.08 m; 10 m/s for 10 s moves ~100 m ~= 0.0008993 deg.
        val step = 100.0 / 111_195.08
        for (i in 0..10) metrics.onSample(sampleAt(i * 10_000L, i * step, 0.0))

        val snapshot = metrics.snapshot()
        assertEquals(1000.0, snapshot.distanceM, 1.0)
        assertEquals(100_000L, snapshot.elapsedMs)
        assertEquals(0L, snapshot.pausedMs)
        assertEquals(100_000L, snapshot.movingMs)
        assertEquals(0L, snapshot.stationaryMs)
        assertEquals(10.0, snapshot.averageSpeedMps, 0.1)
        assertEquals(100.0, snapshot.paceSecPerKm!!, 1.0)
    }

    @Test
    fun pauseIsExcludedFromDistanceAndMovingTime() {
        val metrics = ActivityMetrics()
        val step = 100.0 / 111_195.08
        metrics.onSample(sampleAt(0, 0.0, 0.0))
        metrics.onSample(sampleAt(10_000, step, 0.0)) // 100 m in 10 s
        metrics.onPause(10_000)
        metrics.onResume(70_000) // 60 s paused
        metrics.onSample(sampleAt(70_000, step, 0.0)) // resumes at the same spot: no leg counted (no phantom leg)
        metrics.onSample(sampleAt(80_000, 2 * step, 0.0)) // another 100 m in 10 s

        val snapshot = metrics.snapshot()
        assertEquals(200.0, snapshot.distanceM, 1.0)
        assertEquals(80_000L, snapshot.elapsedMs)
        assertEquals(60_000L, snapshot.pausedMs)
        assertEquals(20_000L, snapshot.movingMs)
    }

    @Test
    fun legsBelowStationaryThresholdDontCountAsMoving() {
        val metrics = ActivityMetrics()
        metrics.onSample(sampleAt(0, 0.0, 0.0))
        metrics.onSample(sampleAt(60_000, 0.0, 0.0)) // stayed put for 60 s
        val snapshot = metrics.snapshot()
        assertEquals(60_000L, snapshot.stationaryMs)
        assertEquals(0L, snapshot.movingMs)
        assertEquals(0.0, snapshot.distanceM, 0.0)
    }

    @Test
    fun paceIsUndefinedWhenNothingMoved() {
        val metrics = ActivityMetrics()
        metrics.onSample(sampleAt(0, 0.0, 0.0))
        assertNull(metrics.snapshot().paceSecPerKm)
    }
}
