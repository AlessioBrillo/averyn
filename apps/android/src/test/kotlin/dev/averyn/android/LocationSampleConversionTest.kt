package dev.averyn.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocationSampleConversionTest {
    private fun sample(
        accuracyM: Double? = 5.0,
        altitudeM: Double? = null,
        speedMps: Double? = null,
        bearingDeg: Double? = null,
    ) = buildLocationSample(
        timeMs = 1_000,
        elapsedRealtimeMs = 2_000,
        latitude = 45.0,
        longitude = 9.0,
        accuracyM = accuracyM,
        altitudeM = altitudeM,
        speedMps = speedMps,
        bearingDeg = bearingDeg,
    )

    @Test
    fun missingAccuracyBecomesVeryPoorNeverZero() {
        assertEquals(9_999.0, sample(accuracyM = null).horizontalAccuracyM)
    }

    @Test
    fun reportedAccuracyPassesThrough() {
        assertEquals(12.5, sample(accuracyM = 12.5).horizontalAccuracyM)
    }

    @Test
    fun missingOptionalFieldsStayNull() {
        val result = sample(altitudeM = null, speedMps = null, bearingDeg = null)
        assertNull(result.altitudeM)
        assertNull(result.speedMps)
        assertNull(result.bearingDeg)
    }

    @Test
    fun reportedOptionalFieldsPassThrough() {
        val result = sample(altitudeM = 100.0, speedMps = 3.5, bearingDeg = 90.0)
        assertEquals(100.0, result.altitudeM)
        assertEquals(3.5, result.speedMps)
        assertEquals(90.0, result.bearingDeg)
    }
}
