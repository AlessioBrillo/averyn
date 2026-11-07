package dev.averyn.backend.synapse

import dev.averyn.sensors.SynapseRawStream
import dev.averyn.sensors.readSynapseSidecar
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The derived view of a Synapse raw sidecar, on the 60 s synthetic session of the device repository (an ECG with one
 * 1 mV R wave per second, a restart, four lost frames, nine samples dropped by the band, ...). Nothing here is a
 * measurement and nothing has run on a band.
 */
class SynapseRecordingTest {
    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/synapse/$name")) { "missing $name" }.readBytes()

    private val recording = SynapseRecording.read(resource("session_60s.synapse-raw.bin"))

    @Test
    fun theSummaryCountsWhatTheDeviceRepositorysAnswerKeyCounts() {
        val streams = recording.summary().streams.associateBy { it.stream }
        assertEquals(setOf("ecg", "ppg", "acc", "gyro", "mag"), streams.keys)
        val ecg = streams.getValue("ecg")
        assertEquals(463, ecg.frames)
        assertEquals(29_632L, ecg.values)
        assertEquals(4, ecg.lostFrames)
        assertEquals(1, ecg.restarts)
        assertEquals(9, streams.getValue("ppg").deviceDropped)
        assertEquals(1, streams.getValue("acc").outOfOrder)
        assertEquals(mapOf("unknown_version" to 1), recording.invalidFrames)
        assertTrue(recording.durationMs in 59_000..61_000, "a minute: ${recording.durationMs}")
    }

    @Test
    fun everyHoleIsAGapWithItsReasonAndAnEndedSegment() {
        val ecg = recording.summary().streams.single { it.stream == "ecg" }
        assertEquals(listOf("restart", "lost_frames"), ecg.gaps.map { it.reason })
        assertEquals(4, ecg.gaps.single { it.reason == "lost_frames" }.count)
        val ppg = recording.summary().streams.single { it.stream == "ppg" }
        assertEquals(listOf("device_dropped"), ppg.gaps.map { it.reason })
        assertEquals(9, ppg.gaps.single().count)
        val gyro = recording.summary().streams.single { it.stream == "gyro" }
        assertTrue(gyro.gaps.isEmpty(), "a clean stream has no gap")
    }

    @Test
    fun aWindowIsNeverDrawnAcrossAGap() {
        val window = assertNotNull(recording.window(SynapseRawStream.ECG, 0, 61_000, 4_000))
        assertEquals(3, window.segments.size, "the ECG has a restart and a hole: three runs")
        val starts = window.segments.map { it.t0Ms }
        assertEquals(starts.sorted(), starts)
        for ((a, b) in window.segments.zipWithNext()) {
            assertTrue(b.t0Ms >= a.t0Ms + a.min[0].size * a.bucketMs - 1e-6, "segments do not overlap")
        }
        assertEquals(window.gaps.size, 2)
    }

    @Test
    fun theFinestLevelIsTheSignalItself() {
        // 500 Hz over one second is 500 samples: under the budget, so every sample is a bucket of one.
        val window = assertNotNull(recording.window(SynapseRawStream.ECG, 2_000, 3_000, 4_000))
        val segment = window.segments.first()
        assertEquals(1, segment.bucketSamples)
        assertEquals(1000.0 / 500, segment.bucketMs, 1e-9)
        assertContentEquals(segment.min[0], segment.max[0])
    }

    @Test
    fun aCoarseWindowKeepsThePeaksThatAnAverageWouldLose() {
        // The whole minute in at most 100 buckets per run: far coarser than the 2 ms of the signal, and yet each run
        // must still show the 1 mV R waves, because a bucket keeps its minimum and its maximum.
        val window = assertNotNull(recording.window(SynapseRawStream.ECG, 0, 61_000, 100))
        for (segment in window.segments) {
            assertTrue(segment.bucketSamples >= 10, "coarser than the signal: ${segment.bucketSamples}")
            assertTrue(segment.min[0].size <= 100, "within the budget: ${segment.min[0].size}")
            assertTrue((segment.max[0].max()) >= 900.0, "the R wave survives: ${segment.max[0].max()}")
        }
        for (segment in window.segments) {
            for (i in segment.min[0].indices) {
                assertTrue(
                    segment.min[0][i] <= segment.max[0][i],
                    "min <= max in every bucket",
                )
            }
        }
    }

    @Test
    fun valuesAreInTheContractsDisplayUnitsNotTheWiresAndAGapIsNotZero() {
        val acc = assertNotNull(recording.window(SynapseRawStream.ACC, 0, 61_000, 4_000))
        assertEquals(listOf("mg", "mg", "mg"), acc.channels.map { it.unit })
        // gravity on Z: about 1000 mg, in mg as it travels
        assertTrue(
            acc.segments
                .first()
                .max[2]
                .all { it in 990.0..1010.0 },
        )
        val gyro = assertNotNull(recording.window(SynapseRawStream.GYRO, 0, 61_000, 4_000))
        assertEquals(listOf("dps", "dps", "dps"), gyro.channels.map { it.unit })
        // around zero mrad/s on the wire (+-2), a handful of mdps once converted to degrees per second
        assertTrue(
            gyro.segments
                .first()
                .max[0]
                .all { kotlin.math.abs(it) < 0.2 },
        )
    }

    @Test
    fun aWindowOutsideTheRecordingOrOfAnAbsentStreamIsEmptyOrNull() {
        val after = assertNotNull(recording.window(SynapseRawStream.ECG, 120_000, 130_000, 100))
        assertTrue(after.segments.isEmpty())
        assertNull(recording.window(SynapseRawStream.POWER, 0, 61_000, 100), "the session has no power stream")
    }

    @Test
    fun aCutTailOrGarbageDoesNotBreakTheReader() {
        val whole = resource("session_60s.synapse-raw.bin")
        val cut = SynapseRecording.read(whole.copyOf(whole.size - 7))
        assertEquals(
            readSynapseSidecar(whole).records.size - 1,
            readSynapseSidecar(whole.copyOf(whole.size - 7)).records.size,
            "the half-written last record is the only one lost",
        )
        assertTrue(cut.summary().streams.isNotEmpty())
        assertTrue(
            SynapseRecording
                .read(ByteArray(0))
                .summary()
                .streams
                .isEmpty(),
        )
        assertTrue(
            SynapseRecording
                .read(ByteArray(5) { 1 })
                .summary()
                .streams
                .isEmpty(),
        )
    }
}
