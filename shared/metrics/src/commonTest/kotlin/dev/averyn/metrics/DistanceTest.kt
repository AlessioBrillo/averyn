package dev.averyn.metrics

import dev.averyn.domain.LocationSample
import kotlin.test.Test
import kotlin.test.assertEquals

class DistanceTest {
    private fun at(
        lat: Double,
        lon: Double,
    ) = LocationSample(0, 0, lat, lon, 5.0)

    @Test
    fun oneDegreeOfLatitudeIsAbout111km() {
        // R * pi/180 with R = 6_371_008.8 m => 111_195.08 m
        assertEquals(111_195.08, haversineMeters(0.0, 0.0, 1.0, 0.0), 0.5)
    }

    @Test
    fun oneDegreeOfLongitudeShrinksWithLatitude() {
        val atEquator = haversineMeters(0.0, 0.0, 0.0, 1.0)
        val at60 = haversineMeters(60.0, 0.0, 60.0, 1.0)
        assertEquals(0.5, at60 / atEquator, 0.001) // cos(60°) = 0.5
    }

    @Test
    fun sumsLegsAndHandlesShortInputs() {
        val path = listOf(at(0.0, 0.0), at(1.0, 0.0), at(2.0, 0.0))
        assertEquals(2 * 111_195.08, distanceMeters(path), 1.0)
        assertEquals(0.0, distanceMeters(emptyList()))
        assertEquals(0.0, distanceMeters(listOf(at(1.0, 1.0))))
    }
}
