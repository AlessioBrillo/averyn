package dev.averyn.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The profile contract (TDD-0012): the JSON, the defaults and the bounds of the maximum heart rate. */
class UserProfileTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `the JSON uses the lower-case unit names the contract promises`() {
        val text = json.encodeToString(UserProfile.serializer(), UserProfile(UnitSystem.IMPERIAL, 187))
        assertEquals("""{"units":"imperial","maxHrBpm":187}""", text)
        assertEquals(UserProfile(UnitSystem.IMPERIAL, 187), json.decodeFromString(UserProfile.serializer(), text))
    }

    @Test
    fun `a body without fields is the default profile`() {
        assertEquals(UserProfile(), json.decodeFromString(UserProfile.serializer(), "{}"))
        assertEquals(UnitSystem.METRIC, UserProfile().units)
        assertNull(UserProfile().maxHrBpm)
    }

    @Test
    fun `the maximum heart rate is valid from 100 to 230 and not outside`() {
        assertNull(UserProfile(maxHrBpm = null).problem())
        assertNull(UserProfile(maxHrBpm = 100).problem())
        assertNull(UserProfile(maxHrBpm = 230).problem())
        for (bad in listOf(99, 231, 0, -1, 1000)) {
            assertNotNull(UserProfile(maxHrBpm = bad).problem(), "$bad")
        }
    }

    @Test
    fun `an empty field means no maximum and a whole number in range is that number`() {
        assertEquals(MaxHrInput(valid = true, value = null), parseMaxHr(""))
        assertEquals(MaxHrInput(valid = true, value = null), parseMaxHr("  "))
        assertEquals(MaxHrInput(valid = true, value = 187), parseMaxHr("187"))
        assertEquals(MaxHrInput(valid = true, value = 100), parseMaxHr(" 100 "))
        assertEquals(MaxHrInput(valid = true, value = 230), parseMaxHr("230"))
    }

    @Test
    fun `anything else in the field is refused`() {
        for (bad in listOf("99", "231", "-5", "18.5", "1e2", "fast", "0x90", "12345", "99999999999")) {
            assertEquals(MaxHrInput(valid = false, value = null), parseMaxHr(bad), bad)
        }
    }
}
