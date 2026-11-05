package dev.averyn.analysis

import dev.averyn.api.PrivacyZone
import dev.averyn.api.Track
import dev.averyn.api.TrackGeometry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class PrivacyZoneCutTest {
    // One step of 0.001 degrees of latitude is ~111 m. Points are [longitude, latitude].
    private fun at(steps: Int) = listOf(9.0, 45.0 + steps * 0.001)

    private fun track(vararg lines: List<List<Double>>) = Track(geometry = TrackGeometry(coordinates = lines.toList()))

    private val homeAtStart = PrivacyZone(45.0, 9.0, 250) // covers steps 0, 1 and 2 (0, 111, 222 m)

    @Test
    fun theStartInsideAZoneIsHiddenAndTheRestStays() {
        val cut = track(List(8) { at(it) }).hidingZones(listOf(homeAtStart))

        assertEquals(listOf(List(5) { at(it + 3) }), cut.geometry?.coordinates)
    }

    @Test
    fun aStretchInTheMiddleBreaksTheLineInsteadOfBeingCrossed() {
        val zone = PrivacyZone(45.0 + 5 * 0.001, 9.0, 120) // covers steps 4, 5 and 6
        val cut = track(List(12) { at(it) }).hidingZones(listOf(zone))

        assertEquals(listOf(List(4) { at(it) }, List(5) { at(it + 7) }), cut.geometry?.coordinates)
    }

    @Test
    fun aPieceWithASinglePointGoesAndAnEntirelyHiddenTrackHasNoGeometry() {
        val zone = PrivacyZone(45.0, 9.0, 2000)

        assertNull(track(List(5) { at(it) }).hidingZones(listOf(zone)).geometry)
        // A lone point between two hidden stretches is not a line.
        val gaps = PrivacyZone(45.0 + 1 * 0.001, 9.0, 60)
        val lonely = track(listOf(at(1), at(2), at(3)))
        assertEquals(listOf(listOf(at(2), at(3))), lonely.hidingZones(listOf(gaps)).geometry?.coordinates)
    }

    @Test
    fun everyLineIsCutOnItsOwnAndNoZoneChangesNothing() {
        val first = List(6) { at(it) }
        val second = List(4) { at(it + 20) }
        val original = track(first, second)

        assertSame(original, original.hidingZones(emptyList()))
        assertEquals(
            listOf(List(3) { at(it + 3) }, second),
            original.hidingZones(listOf(homeAtStart)).geometry?.coordinates,
        )
        // The input is not changed: a zone is a view, never an edit of the data.
        assertEquals(listOf(first, second), original.geometry?.coordinates)
    }

    @Test
    fun aTrackWithoutGeometryIsReturnedAsItIs() {
        val empty = Track(geometry = null)

        assertSame(empty, empty.hidingZones(listOf(homeAtStart)))
    }
}
