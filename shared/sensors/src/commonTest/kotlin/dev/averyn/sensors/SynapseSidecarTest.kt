package dev.averyn.sensors

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The record format the phone appends while recording a band (ADR-0030). */
class SynapseSidecarTest {
    private fun frame(n: Int) = ByteArray(n) { (it + n).toByte() }

    @Test
    fun recordsRoundTripInOrder() {
        val blob =
            encodeSynapseSidecarRecord(1_000_000L, frame(27)) +
                encodeSynapseSidecarRecord(2_500_000_000L, frame(40)) +
                encodeSynapseSidecarRecord(Long.MAX_VALUE, frame(1))

        val read = readSynapseSidecar(blob)

        assertEquals(0, read.trailingBytes)
        assertEquals(listOf(1_000_000L, 2_500_000_000L, Long.MAX_VALUE), read.records.map { it.receivedNanos })
        assertContentEquals(frame(27), read.records[0].frame)
        assertContentEquals(frame(40), read.records[1].frame)
        assertContentEquals(frame(1), read.records[2].frame)
    }

    @Test
    fun theLayoutIsLengthThenTimeThenFrameLittleEndian() {
        val record = encodeSynapseSidecarRecord(0x0102030405060708L, byteArrayOf(9, 8, 7))

        assertContentEquals(byteArrayOf(3, 0, 8, 7, 6, 5, 4, 3, 2, 1, 9, 8, 7), record)
    }

    @Test
    fun anAppendOnlyFileCutInsideARecordKeepsTheWholeOnesAndReportsTheTail() {
        val whole = encodeSynapseSidecarRecord(10L, frame(27))
        val cut = encodeSynapseSidecarRecord(20L, frame(27)).copyOf(15) // the app was killed while writing

        val read = readSynapseSidecar(whole + cut)

        assertEquals(1, read.records.size)
        assertEquals(15, read.trailingBytes)
    }

    @Test
    fun aTailShorterThanAHeaderIsReportedToo() {
        val read = readSynapseSidecar(encodeSynapseSidecarRecord(10L, frame(5)) + byteArrayOf(1, 2, 3))

        assertEquals(1, read.records.size)
        assertEquals(3, read.trailingBytes)
    }

    @Test
    fun anEmptyFileHasNoRecords() {
        val read = readSynapseSidecar(ByteArray(0))

        assertEquals(0, read.records.size)
        assertEquals(0, read.trailingBytes)
    }

    @Test
    fun aFrameThatDoesNotFitOrANegativeTimeIsRefused() {
        assertFailsWith<IllegalArgumentException> {
            encodeSynapseSidecarRecord(
                0,
                ByteArray(
                    SYNAPSE_SIDECAR_MAX_FRAME_BYTES + 1,
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> { encodeSynapseSidecarRecord(-1, frame(1)) }
    }
}
