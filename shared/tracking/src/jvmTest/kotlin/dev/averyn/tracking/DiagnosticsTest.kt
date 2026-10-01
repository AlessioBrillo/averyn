package dev.averyn.tracking

import dev.averyn.domain.LocationSample
import dev.averyn.domain.Sport
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticsTest {
    private lateinit var dir: java.nio.file.Path
    private lateinit var store: ActivityStore

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("averyn-diagnostics-test")
        store = ActivityStore(Path(dir.toString()))
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun sample(
        elapsedMs: Long,
        lat: Double,
    ) = LocationSample(elapsedMs, elapsedMs, lat, 0.0, 5.0)

    @Test
    fun liveActivityComputesDiagnosticsCountersAndBatteryDrain() {
        val recorder = ActivityRecorder(store)
        var battery = 100
        recorder.batteryPercent = { battery }

        recorder.start("a1", Sport.RUN, 0, 0)
        recorder.gpsReady(100, 100)
        recorder.onSample(sample(100, 0.0))
        battery = 90
        recorder.onSample(sample(10_100, 0.0))

        val completed = recorder.stop(10_100, 10_100)
        val diag = completed.diagnostics

        assertEquals(DIAGNOSTICS_FORMAT_VERSION, diag.formatVersion)
        assertEquals(2, diag.totalSamples)
        assertEquals(2, diag.recordingSamples)
        assertEquals(emptyMap(), diag.recordLosses)
        assertEquals(0, diag.recoveries)
        assertEquals(100, diag.batteryStartPercent)
        assertEquals(90, diag.batteryEndPercent)
        // elapsed = 10_100 - 100 = 10_000 ms = 10 s; drain = 10 points / (10_000 / 3_600_000) h = 3_600 %/h.
        assertEquals(3_600.0, diag.batteryDrainPercentPerHour!!, 0.1)
    }

    @Test
    fun recoveryTracksLossesByReasonAndRecoveryCount() {
        val original = ActivityRecorder(store)
        original.start("a1", Sport.RUN, 0, 0)
        original.gpsReady(0, 0)
        original.onSample(sample(0, 0.0))
        // Simulate a kill mid-write: an incomplete trailing SAMPLE line (same technique as ActivityStoreTest).
        val path = Path(dir.toString(), "a1.jsonl")
        SystemFileSystem.sink(path, append = true).buffered().use { it.writeString("SAMPLE {\"timeMs\":1,\"elapsedRe") }

        val fresh = ActivityRecorder(store)
        val result = fresh.recover("a1", 20_000, 20_000)
        assertTrue(result is RecoveryResult.Resumed)
        assertEquals(1, result.droppedRecordCount)

        val completed = fresh.stop(30_000, 30_000)
        assertEquals(mapOf(RecordLossReason.TRUNCATED_RECORD to 1), completed.diagnostics.recordLosses)
        assertEquals(1, completed.diagnostics.recoveries)
    }

    @Test
    fun diagnosticsJsonCarriesNoCoordinates() {
        val recorder = ActivityRecorder(store)
        recorder.start("a1", Sport.RUN, 0, 0)
        recorder.gpsReady(0, 0)
        recorder.onSample(sample(0, 45.0))
        recorder.onSample(sample(1_000, 45.001))
        val completed = recorder.stop(1_000, 1_000)

        val json = completed.diagnostics.toJson(DeviceInfo("Pixel", "14", "0.0.1"))

        assertFalse("latitude" in json, "diagnostics export must not carry coordinates: $json")
        assertFalse("longitude" in json, "diagnostics export must not carry coordinates: $json")
    }
}
