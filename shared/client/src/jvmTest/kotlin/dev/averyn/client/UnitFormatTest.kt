package dev.averyn.client

import dev.averyn.api.UnitSystem
import kotlin.test.Test
import kotlin.test.assertEquals

/** The examples are the ones of `apps/web/src/format.test.ts`: both clients must say the same thing. */
class UnitFormatTest {
    private val metric = UnitFormat(UnitSystem.METRIC)
    private val imperial = UnitFormat(UnitSystem.IMPERIAL)

    @Test
    fun metricDistancesAreMetresBelowAKilometreAndKilometresAbove() {
        assertEquals("0 m", metric.distance(0.0))
        assertEquals("950 m", metric.distance(950.4))
        assertEquals("1.00 km", metric.distance(1000.0))
        assertEquals("10.23 km", metric.distance(10_234.0))
    }

    @Test
    fun imperialDistancesAreMiles() {
        assertEquals("1.00 mi", imperial.distance(1609.344))
        assertEquals("6.21 mi", imperial.distance(10_000.0))
        assertEquals("0.31 mi", imperial.distance(500.0))
    }

    @Test
    fun paceIsPerKilometreOrPerMile() {
        assertEquals("5:00 /km", metric.pace(300.0))
        assertEquals("6:00 /km", metric.pace(359.6))
        assertEquals("8:03 /mi", imperial.pace(300.0))
        assertEquals("1:05 /km", metric.pace(65.0))
    }

    @Test
    fun speedIsKilometresPerHourOrMilesPerHour() {
        assertEquals("36.0 km/h", metric.speed(10.0))
        assertEquals("22.4 mph", imperial.speed(10.0))
    }

    @Test
    fun elevationIsMetresOrFeetWithAnOptionalSign() {
        assertEquals("100 m", metric.elevation(100.0))
        assertEquals("328 ft", imperial.elevation(100.0))
        assertEquals("+120 m", metric.elevation(120.0, signed = true))
        assertEquals("-35 m", metric.elevation(-35.0, signed = true))
        assertEquals("0 m", metric.elevation(0.0, signed = true))
        assertEquals("-100 ft", imperial.elevation(-30.48, signed = true))
    }

    @Test
    fun theFixedFormatKeepsItsDecimalsAndRoundsHalfAwayFromZero() {
        assertEquals("0.05", fixed(0.05, 2))
        assertEquals("2.00", fixed(1.999, 2))
        assertEquals("-0.50", fixed(-0.5, 2))
        assertEquals("0.0", fixed(-0.001, 1)) // no "-0.0"
        assertEquals("7", fixed(6.6, 0))
    }

    @Test
    fun theDefaultIsMetric() {
        assertEquals("1.00 km", UnitFormat().distance(1000.0))
    }
}
