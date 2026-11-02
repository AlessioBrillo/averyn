package dev.averyn.android

import kotlin.test.Test
import kotlin.test.assertEquals

class OptionalPermissionsTest {
    @Test
    fun asksOnlyForWhatTheVersionHasAndWhatIsMissing() {
        // Android 11: neither notifications nor Bluetooth permissions exist.
        assertEquals(emptyList(), optionalPermissionsMissing(30) { false })
        // Android 12: Bluetooth only.
        assertEquals(
            listOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"),
            optionalPermissionsMissing(31) { false },
        )
        // Android 14 with the Bluetooth ones already granted: only the notifications.
        assertEquals(
            listOf("android.permission.POST_NOTIFICATIONS"),
            optionalPermissionsMissing(34) { it.contains("BLUETOOTH") },
        )
        assertEquals(emptyList(), optionalPermissionsMissing(34) { true })
    }
}
