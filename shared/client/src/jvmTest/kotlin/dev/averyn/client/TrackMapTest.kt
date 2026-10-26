package dev.averyn.client

import dev.averyn.api.Track
import dev.averyn.api.TrackGeometry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The track on the phone map (TDD-0012): the box to show and the GeoJSON to draw. */
class TrackMapTest {
    private fun track(vararg lines: List<List<Double>>) = Track(geometry = TrackGeometry(coordinates = lines.toList()))

    @Test
    fun theBoxHoldsEveryPointOfEveryLine() {
        val map =
            assertNotNull(
                trackMapOf(
                    track(
                        listOf(listOf(9.10, 45.40), listOf(9.12, 45.41)),
                        listOf(listOf(9.20, 45.30), listOf(9.15, 45.50)),
                    ),
                ),
            )

        assertEquals(45.30, map.minLatitude)
        assertEquals(45.50, map.maxLatitude)
        assertEquals(9.10, map.minLongitude)
        assertEquals(9.20, map.maxLongitude)
    }

    @Test
    fun theGeoJsonIsAFeatureWithOneLinePerRecordingInterval() {
        val map =
            assertNotNull(
                trackMapOf(
                    track(listOf(listOf(9.1, 45.4), listOf(9.2, 45.5)), listOf(listOf(9.3, 45.6), listOf(9.4, 45.7))),
                ),
            )

        assertTrue(map.geoJson.contains("\"type\":\"Feature\""), map.geoJson)
        assertTrue(map.geoJson.contains("\"type\":\"MultiLineString\""), map.geoJson)
        assertTrue(map.geoJson.contains("[[[9.1,45.4],[9.2,45.5]],[[9.3,45.6],[9.4,45.7]]]"), map.geoJson)
    }

    @Test
    fun anActivityWithoutATrackHasNoMap() {
        assertNull(trackMapOf(null))
        assertNull(trackMapOf(Track(geometry = null)))
        assertNull(trackMapOf(track()))
        assertNull(trackMapOf(track(emptyList())))
    }

    @Test
    fun aBadCoordinateIsSkippedAndDoesNotHideTheTrack() {
        val map =
            assertNotNull(
                trackMapOf(
                    track(
                        listOf(
                            listOf(9.1, 45.4),
                            listOf(9.2),
                            listOf(Double.NaN, 45.0),
                            listOf(200.0, 45.0),
                            listOf(9.2, 95.0),
                            listOf(9.3, 45.5),
                        ),
                    ),
                ),
            )

        assertEquals(45.4, map.minLatitude)
        assertEquals(45.5, map.maxLatitude)
        assertEquals(9.1, map.minLongitude)
        assertEquals(9.3, map.maxLongitude)
    }

    @Test
    fun aTrackOfBadPointsOnlyHasNoMap() {
        assertNull(trackMapOf(track(listOf(listOf(9.2), listOf(300.0, 10.0)))))
    }

    @Test
    fun theGeoJsonHoldsOnlyTheValidPoints() {
        val map =
            assertNotNull(
                trackMapOf(track(listOf(listOf(9.1, 45.4), listOf(Double.NaN, 45.0), listOf(9.3, 45.5, 120.0)))),
            )

        // The NaN point is gone, the altitude of the third is not part of the drawing.
        assertTrue(map.geoJson.contains("[[[9.1,45.4],[9.3,45.5]]]"), map.geoJson)
    }
}
