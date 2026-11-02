package dev.averyn.sensors

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeartRateFrameParserTest {
    /** Builds a `0x2A37` notification: flags + hr value [+ energy][+ rr intervals...]. */
    private fun frame(
        uint16: Boolean = false,
        contactDetected: Boolean? = null,
        energyPresent: Boolean = false,
        rrPresent: Boolean = false,
        bpm: Int = 137,
        energy: Int = 0,
        vararg rr: Int,
    ): ByteArray {
        var flags = 0
        if (uint16) flags = flags or 0x01
        flags =
            when (contactDetected) {
                null -> flags or 0x00 // not supported
                true -> flags or 0x02 // bit 1 = 1, bit 2 = 0 => contact detected
                false -> flags or 0x04 // bit 1 = 0, bit 2 = 1 => contact not detected
            }
        if (energyPresent) flags = flags or 0x08
        if (rrPresent) flags = flags or 0x10
        val bytes = mutableListOf(flags.toByte())
        if (uint16) {
            bytes += (bpm and 0xFF).toByte()
            bytes += ((bpm shr 8) and 0xFF).toByte()
        } else {
            bytes += bpm.toByte()
        }
        if (energyPresent) {
            bytes += (energy and 0xFF).toByte()
            bytes += ((energy shr 8) and 0xFF).toByte()
        }
        for (r in rr) {
            bytes += (r and 0xFF).toByte()
            bytes += ((r shr 8) and 0xFF).toByte()
        }
        return bytes.toByteArray()
    }

    @Test
    fun parsesUint8HeartRateWithoutOptionalFields() {
        val data = frame(uint16 = false, contactDetected = null, bpm = 137)
        val (results, consumed) = parseHeartRateFrames(data, 0, 1000L, 2000L)
        assertEquals(data.size, consumed)
        assertEquals(1, results.size)
        val complete =
            results[0] as? ParseResult.Complete
                ?: error("expected Complete, got $results")
        assertEquals(137, complete.heartRate?.bpm)
        assertEquals(null, complete.heartRate?.contact)
        assertEquals(null, complete.energyExpended)
        assertEquals(emptyList(), complete.rrIntervals)
    }

    @Test
    fun parsesUint16HeartRateWithContactDetectedAndEnergy() {
        val data = frame(uint16 = true, contactDetected = true, energyPresent = true, bpm = 180, energy = 42)
        val (results, consumed) = parseHeartRateFrames(data, 0, 1000L, 2000L)
        assertEquals(data.size, consumed)
        val complete =
            results[0] as? ParseResult.Complete
                ?: error("expected Complete")
        assertEquals(180, complete.heartRate?.bpm)
        assertEquals(true, complete.heartRate?.contact)
        assertEquals(42, complete.energyExpended)
    }

    @Test
    fun parsesRrIntervals() {
        // On the wire the unit is 1/1024 s: 1024 is one second, 877 is 856.4 ms.
        val data = frame(rrPresent = true, bpm = 70, rr = *intArrayOf(877, 819, 1024))
        val (results, consumed) = parseHeartRateFrames(data, 0, 1000L, 2000L)
        assertEquals(data.size, consumed)
        val complete =
            results[0] as? ParseResult.Complete
                ?: error("expected Complete")
        assertEquals(listOf(856, 800, 1000), complete.rrIntervals.map { it.rrMs })
        assertEquals(70, complete.heartRate?.bpm)
    }

    @Test
    fun aBpmOutOfRangeIsReportedNotThrown() {
        val data = frame(uint16 = true, bpm = 301, rrPresent = true, rr = *intArrayOf(1024))
        val (results, consumed) = parseHeartRateFrames(data, 0, 1000L, 2000L)
        assertEquals(data.size, consumed)
        assertEquals(1, results.count { it is ParseResult.Invalid })
        val complete = results.filterIsInstance<ParseResult.Complete>().single()
        assertEquals(null, complete.heartRate)
        assertEquals(listOf(1000), complete.rrIntervals.map { it.rrMs })
    }

    @Test
    fun anRrOutOfRangeIsReportedNotThrown() {
        // 0 and 4000 (3.9 s) are not intervals between two beats; 1024 is.
        val data = frame(bpm = 70, rrPresent = true, rr = *intArrayOf(0, 4096, 1024))
        val (results, consumed) = parseHeartRateFrames(data, 0, 1000L, 2000L)
        assertEquals(data.size, consumed)
        assertEquals(2, results.count { it is ParseResult.Invalid })
        val complete = results.filterIsInstance<ParseResult.Complete>().single()
        assertEquals(70, complete.heartRate?.bpm)
        assertEquals(listOf(1000), complete.rrIntervals.map { it.rrMs })
    }

    @Test
    fun reportsIncompleteOnTruncatedHrValue() {
        // flags + only 0 bytes of a uint16 value
        val data = byteArrayOf(0x01)
        val (results, consumed) = parseHeartRateFrames(data, 0, 1000L, 2000L)
        assertEquals(0, consumed)
        assertEquals(1, results.size)
        assertTrue(results[0] is ParseResult.Incomplete)
    }

    @Test
    fun reportsIncompleteOnTruncatedRr() {
        // flags + hr(uint8) + rr present + only 1 byte of the rr pair
        val data = byteArrayOf(0x10, 70, 0x01)
        val (results, consumed) = parseHeartRateFrames(data, 0, 1000L, 2000L)
        assertEquals(0, consumed)
        assertTrue(results.any { it is ParseResult.Incomplete })
    }

    @Test
    fun parsesBackToBackFrames() {
        val f1 = frame(bpm = 100)
        val f2 = frame(uint16 = true, bpm = 110)
        val data = f1 + f2
        val (results, consumed) = parseHeartRateFrames(data, 0, 1000L, 2000L)
        assertEquals(data.size, consumed)
        assertEquals(2, results.size)
        val c1 =
            results[0] as? ParseResult.Complete
                ?: error("expected Complete")
        val c2 =
            results[1] as? ParseResult.Complete
                ?: error("expected Complete")
        assertEquals(100, c1.heartRate?.bpm)
        assertEquals(110, c2.heartRate?.bpm)
    }

    @Test
    fun contactNotDetectedMapsToFalse() {
        val data = frame(contactDetected = false, bpm = 90)
        val (results, _) = parseHeartRateFrames(data, 0, 1000L, 2000L)
        val complete =
            results[0] as? ParseResult.Complete
                ?: error("expected Complete")
        assertEquals(false, complete.heartRate?.contact)
    }

    @Test
    fun bpmZeroIsAllowed() {
        val data = frame(bpm = 0)
        val (results, _) = parseHeartRateFrames(data, 0, 1000L, 2000L)
        val complete =
            results[0] as? ParseResult.Complete
                ?: error("expected Complete")
        assertEquals(0, complete.heartRate?.bpm)
    }
}
