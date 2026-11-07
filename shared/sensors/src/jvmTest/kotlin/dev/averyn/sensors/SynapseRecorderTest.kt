package dev.averyn.sensors

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The recorder replays the band session of the device repository (`session_60s.*`, built by its encoder, not by
 * hand): with every consent it must write the two sidecars byte for byte as the fixtures are; without a consent the
 * value never reaches the sink. Nothing here is a measurement and no band was involved.
 */
class SynapseRecorderTest {
    private fun bytes(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/synapse/$name")) { "missing $name" }.readBytes()

    private val all = SynapseConsents(true, true, true, true, true)

    private class Sink : SynapseSidecarSink {
        val out = ByteArrayOutputStream()
        var calls = 0

        override fun append(record: ByteArray) {
            out.write(record)
            calls++
        }
    }

    /** Replays both fixtures in file order; the feature file mixes feature (27 B) and status (40 B) records. */
    private fun replay(consents: SynapseConsents): Pair<Sink, Sink> {
        val features = Sink()
        val raw = Sink()
        val recorder = SynapseRecorder(features, raw, consents)
        for (r in readSynapseSidecar(bytes("session_60s.synapse.bin")).records) {
            val uuid =
                if (r.frame.size == SYNAPSE_STATUS_BYTES) {
                    SYNAPSE_STATUS_CHARACTERISTIC_UUID
                } else {
                    SYNAPSE_FEATURE_CHARACTERISTIC_UUID.uppercase() // iOS hands UUIDs in capitals
                }
            recorder.onNotify(uuid, r.frame, r.receivedNanos)
        }
        val ecg = SYNAPSE_RESEARCH_CHARACTERISTIC_UUIDS.getValue(SynapseRawStream.ECG)
        for (r in readSynapseSidecar(bytes("session_60s.synapse-raw.bin")).records) {
            recorder.onNotify(ecg, r.frame, r.receivedNanos)
        }
        return features to raw
    }

    @Test
    fun withEveryConsentTheSidecarsAreTheFixturesByteForByte() {
        val (features, raw) = replay(all)
        assertContentEquals(bytes("session_60s.synapse.bin"), features.out.toByteArray())
        assertContentEquals(bytes("session_60s.synapse-raw.bin"), raw.out.toByteArray())
    }

    @Test
    fun withoutAnyConsentNothingIsWritten() {
        val (features, raw) = replay(SynapseConsents.NONE)
        assertEquals(0, features.calls)
        assertEquals(0, raw.calls)
    }

    @Test
    fun rawIsNeverWrittenWithoutRawSignals() {
        val (features, raw) = replay(all.copy(rawSignals = false))
        assertEquals(0, raw.calls)
        assertContentEquals(bytes("session_60s.synapse.bin"), features.out.toByteArray())
    }

    @Test
    fun signalQualityAloneWritesFeaturesWithRrHrvAndStressMasked() {
        // The fixture's frames have those fields already "not available", so fill them with a value first.
        val sink = Sink()
        val recorder = SynapseRecorder(sink, Sink(), SynapseConsents(signalQuality = true))
        val fed = mutableListOf<SynapseSidecarRecord>()
        for (r in readSynapseSidecar(bytes("session_60s.synapse.bin")).records) {
            val frame = r.frame.copyOf()
            val isV2Feature = frame.size >= 27 && frame.size != SYNAPSE_STATUS_BYTES && frame[0].toInt() == 2
            if (isV2Feature) for (i in listOf(10, 11, 12, 16, 20, 21, 22, 23)) frame[i] = 0x11
            fed += SynapseSidecarRecord(r.receivedNanos, frame)
            val uuid =
                if (frame.size ==
                    SYNAPSE_STATUS_BYTES
                ) {
                    SYNAPSE_STATUS_CHARACTERISTIC_UUID
                } else {
                    SYNAPSE_FEATURE_CHARACTERISTIC_UUID
                }
            recorder.onNotify(uuid, frame, r.receivedNanos)
        }
        val written = readSynapseSidecar(sink.out.toByteArray()).records
        assertEquals(fed.size, written.size)
        var masked = 0
        for ((o, w) in fed.zip(written)) {
            assertEquals(o.receivedNanos, w.receivedNanos)
            val isV2Feature = o.frame.size >= 27 && o.frame.size != SYNAPSE_STATUS_BYTES && o.frame[0].toInt() == 2
            for (i in o.frame.indices) {
                if (isV2Feature && (i in 10..12 || i == 16 || i in 20..23)) {
                    assertEquals(0xFF.toByte(), w.frame[i], "byte $i of a feature frame is masked")
                    masked++
                } else {
                    assertEquals(o.frame[i], w.frame[i], "byte $i is left as it came")
                }
            }
        }
        assertTrue(masked > 0)
    }

    @Test
    fun eachConsentUnmasksOnlyItsOwnField() {
        val frame = ByteArray(27) { 0x11 }.also { it[0] = 2 }

        fun masked(c: SynapseConsents) = maskSynapseFeatureFrame(frame, c)
        val base = SynapseConsents(signalQuality = true)
        assertEquals(0x11, masked(base.copy(rrRecording = true))[10].toInt())
        assertEquals(0xFF.toByte(), masked(base.copy(rrRecording = true))[20])
        assertEquals(0x11, masked(base.copy(hrvAnalysis = true))[20].toInt())
        assertEquals(0xFF.toByte(), masked(base.copy(hrvAnalysis = true))[16])
        assertEquals(0x11, masked(base.copy(stressMonitoring = true))[16].toInt())
        assertEquals(0xFF.toByte(), masked(base.copy(stressMonitoring = true))[10])
    }

    @Test
    fun aFrameOfAnotherVersionOrAStatusRecordIsNotMasked() {
        val foreign = ByteArray(27) { 0x11 }.also { it[0] = 3 }
        assertContentEquals(foreign, maskSynapseFeatureFrame(foreign, SynapseConsents.NONE))
        val status = ByteArray(SYNAPSE_STATUS_BYTES) { 0x11 }.also { it[0] = 2 }
        assertContentEquals(status, maskSynapseFeatureFrame(status, SynapseConsents.NONE))
    }

    @Test
    fun theNextFrameObeysAConsentRevokedInTheMeantime() {
        val raw = Sink()
        val recorder = SynapseRecorder(Sink(), raw, all)
        val ecg = SYNAPSE_RESEARCH_CHARACTERISTIC_UUIDS.getValue(SynapseRawStream.ECG)
        assertTrue(recorder.onNotify(ecg, byteArrayOf(1), 0))
        recorder.consents = all.copy(rawSignals = false)
        assertFalse(recorder.onNotify(ecg, byteArrayOf(1), 1))
        assertEquals(1, raw.calls)
    }

    @Test
    fun scopesFromTheServerBecomeConsentsAndUnknownOnesAreIgnored() {
        val c = SynapseConsents.fromScopes(listOf("rr_recording", "raw_signals", "something_new"))
        assertEquals(SynapseConsents(rrRecording = true, rawSignals = true), c)
    }

    @Test
    fun anUnrelatedCharacteristicIsNotWritten() {
        val f = Sink()
        assertFalse(SynapseRecorder(f, f, all).onNotify("00002a37-0000-1000-8000-00805f9b34fb", byteArrayOf(1), 0))
        assertEquals(0, f.calls)
    }
}
