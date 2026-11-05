package dev.averyn.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PrivacyZonesTest {
    private val home = PrivacyZone(45.0, 9.0, 200, "Home")

    @Test
    fun noZonesAndAValidZoneAreAccepted() {
        assertNull(PrivacyZones().problem())
        assertNull(PrivacyZones(listOf(home, PrivacyZone(-33.9, 151.2, PrivacyZones.MAX_RADIUS_M))).problem())
    }

    @Test
    fun aZoneOutsideTheRulesIsRefusedWithAReason() {
        val bad =
            listOf(
                PrivacyZones(List(PrivacyZones.MAX_ZONES + 1) { home }),
                PrivacyZones(listOf(home.copy(latitude = 91.0))),
                PrivacyZones(listOf(home.copy(longitude = -180.5))),
                PrivacyZones(listOf(home.copy(latitude = Double.NaN))),
                PrivacyZones(listOf(home.copy(radiusM = PrivacyZones.MIN_RADIUS_M - 1))),
                PrivacyZones(listOf(home.copy(radiusM = PrivacyZones.MAX_RADIUS_M + 1))),
                PrivacyZones(listOf(home.copy(label = "x".repeat(PrivacyZones.MAX_LABEL_LENGTH + 1)))),
            )
        for (zones in bad) assertNotNull(zones.problem(), zones.toString().take(60))
        assertEquals(10, PrivacyZones(List(PrivacyZones.MAX_ZONES) { home }).zones.size)
    }
}
