package dev.averyn.android

import dev.averyn.android.ble.reconnectBackoffMs
import kotlin.test.Test
import kotlin.test.assertEquals

class BleReconnectBackoffTest {
    @Test
    fun doublesFromOneSecondAndStopsAtThirty() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L), (1..6).map { reconnectBackoffMs(it) })
        // A strap that drops for the hundredth time is still retried, at the cap.
        assertEquals(30_000L, reconnectBackoffMs(100))
    }
}
