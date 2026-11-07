package dev.averyn.backend.synapse

import dev.averyn.sensors.SynapseFrameResult
import dev.averyn.sensors.SynapseStress
import dev.averyn.sensors.encodeSynapseSidecarRecord
import dev.averyn.sensors.parseSynapseFeatureFrame
import dev.averyn.sensors.readSynapseSidecar
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The L1 series and the field masking that a consent revocation applies to a stored sidecar (ADR-0021, ADR-0030). */
class SynapseFeaturesTest {
    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/synapse/$name")) { "missing $name" }.readBytes()

    /** A feature frame with every field present: hr 60, rr 1000/1024 s x 1, stress class 1, RMSSD 40, SDNN 50. */
    private fun fullFrame(sequence: Int): ByteArray =
        ByteArray(27).also { f ->
            f[0] = 2
            f[1] = 4 // transmitting
            f[2] = (sequence and 0xFF).toByte()
            f[3] = (sequence shr 8).toByte()
            f[8] = 60
            f[10] = 0xE8.toByte() // 1000
            f[11] = 0x03
            f[12] = 1
            f[13] = 200.toByte()
            f[14] = 0xFF.toByte()
            f[15] = 0xFF.toByte()
            f[16] = 1 // stress
            f[17] = 80
            f[18] = 0xFF.toByte()
            f[19] = 0x7F
            f[20] = 40
            f[22] = 50
            f[24] = 0xFF.toByte()
            f[25] = 0x07
            f[26] = 0xFF.toByte()
        }

    private fun sidecar(vararg frames: ByteArray): ByteArray =
        frames.foldIndexed(ByteArray(0)) { i, acc, f -> acc + encodeSynapseSidecarRecord(i * 1_000_000_000L, f) }

    private fun parsed(frame: ByteArray) = assertIs<SynapseFrameResult.Frame>(parseSynapseFeatureFrame(frame)).frame

    @Test
    fun theSessionIsSeriesWithNullsAndGapsNeverZeros() {
        val features =
            buildFeatures(resource("session_60s.synapse.bin"), FeatureConsent(stress = true, rr = true, bandHrv = true))
        assertEquals(59, features.frames)
        assertEquals(12, features.statusRecords)
        assertEquals(1, features.unknownVersionFrames)
        assertEquals(3, features.lostFrames, "two lost on the link and the one of an unknown version")
        assertEquals(features.frames, features.elapsedMs.size)
        assertEquals(features.frames, features.heartRateBpm.size)
        // the first ten seconds are a warm-up with the quality still unknown
        assertTrue(features.ppgQuality.take(10).all { it == null })
        assertTrue(features.ppgQuality.drop(12).all { it != null })
        // frames 20 and 21 each follow a lost frame, and the frame of an unknown version used a number too
        assertEquals(listOf(1, 1, 1), features.gaps.map { it.count })
        assertTrue(features.gaps.all { it.reason == "lost_frames" })
    }

    @Test
    fun withoutAConsentTheFieldItCoversIsNullWhateverIsStored() {
        val bytes = sidecar(fullFrame(1), fullFrame(2))
        val all = buildFeatures(bytes, FeatureConsent(stress = true, rr = true, bandHrv = true))
        assertEquals("stress", all.stress.first())
        assertEquals(1000 * 1000.0 / 1024.0, checkNotNull(all.rrLastMs.first()), 1e-9) // the wire carries 1/1024 s
        assertEquals(listOf(40, 40), all.rmssdMs)

        val none = buildFeatures(bytes, FeatureConsent(stress = false, rr = false, bandHrv = false))
        assertTrue(none.stress.all { it == null })
        assertTrue(none.rrLastMs.all { it == null })
        assertTrue(none.rmssdMs.all { it == null } && none.sdnnMs.all { it == null })
        assertEquals(listOf(60, 60), none.heartRateBpm, "signal_quality itself still covers heart rate and quality")
    }

    @Test
    fun maskingASetOfFieldsTouchesOnlyThoseFields() {
        val original = sidecar(fullFrame(1), fullFrame(2))
        val masked = maskFeatureSidecar(original, setOf(FeatureField.STRESS))
        for (record in readSynapseSidecar(masked).records) {
            val f = parsed(record.frame)
            assertNull(f.stress)
            assertEquals(1, f.rrCount)
            assertEquals(40, f.rmssdMs)
            assertEquals(60, f.heartRateBpm)
        }
        val rr =
            readSynapseSidecar(
                maskFeatureSidecar(original, setOf(FeatureField.RR)),
            ).records.map { parsed(it.frame) }
        assertTrue(
            rr.all {
                it.rrLastMs == null &&
                    it.rrCount == null &&
                    it.stress == SynapseStress.STRESS &&
                    it.rmssdMs == 40
            },
        )
        val hrv =
            readSynapseSidecar(maskFeatureSidecar(original, setOf(FeatureField.BAND_HRV))).records.map {
                parsed(it.frame)
            }
        assertTrue(hrv.all { it.rmssdMs == null && it.sdnnMs == null && it.rrCount == 1 })
    }

    @Test
    fun maskingLeavesStatusRecordsAndACutTailAlone() {
        val status =
            ByteArray(40).also {
                it[0] = 1
                it[1] = 2
                it[16] = 7
            } // would be 'stress' if it were a feature frame
        val original = sidecar(fullFrame(1), status) + byteArrayOf(9, 0, 1, 2)
        val masked = maskFeatureSidecar(original, FeatureField.entries.toSet())
        assertEquals(original.size, masked.size)
        assertContentEquals(status, readSynapseSidecar(masked).records[1].frame)
        assertContentEquals(
            original.copyOfRange(original.size - 4, original.size),
            masked.copyOfRange(masked.size - 4, masked.size),
        )
    }

    @Test
    fun aRepeatedFrameAddsNoPointAndALongOutageIsOneGap() {
        val bytes = sidecar(fullFrame(10), fullFrame(11), fullFrame(11), fullFrame(15))
        val f = buildFeatures(bytes, FeatureConsent(stress = true, rr = true, bandHrv = true))
        assertEquals(3, f.frames)
        assertEquals(3, f.lostFrames)
        assertEquals(1, f.gaps.size)
        assertEquals(3, f.gaps.single().count)
    }
}
