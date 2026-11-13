package dev.averyn.tracking

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FirstFixTest {
    @Test
    fun aFreshAccurateFixIsUsable() {
        assertTrue(isUsableFirstFix(accuracyM = 8.0, ageMs = 500))
        assertTrue(isUsableFirstFix(accuracyM = FIRST_FIX_MAX_ACCURACY_M, ageMs = FIRST_FIX_MAX_AGE_MS))
    }

    @Test
    fun aCoarseFixIsNot() {
        assertFalse(isUsableFirstFix(accuracyM = 800.0, ageMs = 0)) // a cell-based position
    }

    @Test
    fun aCachedFixIsNot() {
        assertFalse(isUsableFirstFix(accuracyM = 5.0, ageMs = 180_000))
    }

    @Test
    fun aFixWithoutAccuracyIsNot() {
        assertFalse(isUsableFirstFix(accuracyM = null, ageMs = 0))
    }
}
