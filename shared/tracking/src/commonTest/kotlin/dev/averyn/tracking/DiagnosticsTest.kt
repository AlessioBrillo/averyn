package dev.averyn.tracking

import dev.averyn.domain.ActivityState
import dev.averyn.domain.LocationSample
import dev.averyn.domain.Sport
import dev.averyn.sensors.HeartRateSample
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticsTest {
    private lateinit var dir: Path
    private lateinit var store: ActivityStore

    @BeforeTest
    fun setUp() {
        dir = createTestDir("averyn-diagnostics-test")
        store = ActivityStore(dir)
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
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
    fun recoveredEventAfterACutLineIsNotGluedOntoIt() {
        val original = ActivityRecorder(store)
        original.start("a1", Sport.RUN, 0, 0)
        original.gpsReady(0, 0)
        original.onSample(sample(0, 0.0))
        val path = Path(dir, "a1.jsonl")
        SystemFileSystem.sink(path, append = true).buffered().use { it.writeString("SAMPLE {\"timeMs\":1,\"elapsedRe") }

        ActivityRecorder(store).recover("a1", 20_000, 20_000)

        // The RECOVERED event is its own line: a later replay counts one recovery and one loss (the cut line).
        val replayed = replay(store, "a1")
        assertEquals(1, replayed.recoveries)
        assertEquals(mapOf(RecordLossReason.TRUNCATED_RECORD to 1), replayed.recordLosses)
    }

    @Test
    fun recoveryTracksLossesByReasonAndRecoveryCount() {
        val original = ActivityRecorder(store)
        original.start("a1", Sport.RUN, 0, 0)
        original.gpsReady(0, 0)
        original.onSample(sample(0, 0.0))
        // Simulate a kill mid-write: an incomplete trailing SAMPLE line (same technique as ActivityStoreTest).
        val path = Path(dir, "a1.jsonl")
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

        val json = completed.diagnostics.toJson(DeviceInfo("Pixel", "14", "0.0.1", "Google", null, null, null, null))

        assertFalse("latitude" in json, "diagnostics export must not carry coordinates: $json")
        assertFalse("longitude" in json, "diagnostics export must not carry coordinates: $json")
    }

    @Test
    fun v2ReportNamesTheAlgorithmsAndCarriesTheEventTimeline() {
        val recorder = ActivityRecorder(store)
        recorder.start("a1", Sport.RUN, 0, 1_000)
        recorder.gpsReady(0, 1_000)
        recorder.onSample(sample(0, 45.0))
        recorder.pause(5_000, 6_000)
        recorder.resume(9_000, 10_000)
        recorder.onSample(sample(10_000, 45.001))
        val diag = recorder.stop(10_000, 11_000).diagnostics

        assertEquals("diagnostics-v5", diag.formatVersion)
        assertEquals(AlgorithmVersions("distance-v1", "time-v1", "speed-v1", "quality-v2"), diag.algorithms)
        assertEquals(
            listOf(
                ActivityState.PREPARING,
                ActivityState.RECORDING,
                ActivityState.PAUSED,
                ActivityState.RECORDING,
                ActivityState.STOPPING,
                ActivityState.COMPLETED,
            ),
            diag.events.map { it.state },
        )
        assertEquals(11_000L, diag.endedAtMs)
    }

    @Test
    fun fixesTheRecorderCouldNotUseAreCountedByReason() {
        val recorder = ActivityRecorder(store)
        recorder.onSample(sample(0, 45.0)) // no activity yet: a restarted service before recover()
        recorder.start("a1", Sport.RUN, 0, 0)
        recorder.gpsReady(0, 0)
        recorder.onSample(sample(1_000, 45.0))
        recorder.recordDroppedInput(DroppedInputReason.INVALID_SAMPLE)
        val completed = recorder.stop(2_000, 2_000)
        recorder.onSample(sample(3_000, 45.0)) // a late callback after the end

        // start() begins a new activity's counters, so only what happened since then belongs to it.
        assertEquals(mapOf(DroppedInputReason.INVALID_SAMPLE to 1), completed.diagnostics.droppedInputs)
    }

    @Test
    fun aReportRebuiltFromTheStoreEqualsTheOneBuiltLive() {
        val recorder = ActivityRecorder(store)
        recorder.batteryPercent = { 80 }
        recorder.start("a1", Sport.RUN, 0, 1_000)
        recorder.gpsReady(0, 1_000)
        recorder.onSample(sample(0, 45.0))
        recorder.pause(5_000, 6_000)
        recorder.resume(9_000, 10_000)
        recorder.onSample(sample(10_000, 45.001))
        val live = recorder.stop(10_000, 11_000).diagnostics

        val rebuilt = store.diagnosticsOf("a1")

        assertEquals(live, rebuilt) // nothing was dropped before storing, so even droppedInputs matches
        assertEquals(null, store.diagnosticsOf("missing"))
    }

    @Test
    fun cancellingWhileWaitingForGpsKeepsNothing() {
        val recorder = ActivityRecorder(store)
        recorder.start("a1", Sport.RUN, 0, 0)
        assertEquals(1, store.list().size)

        recorder.cancel()

        assertEquals(ActivityState.IDLE, recorder.currentState)
        assertEquals(0, store.list().size)
        recorder.start("a2", Sport.RUN, 10, 10) // and the recorder is reusable
        assertEquals(ActivityState.PREPARING, recorder.currentState)
    }

    @Test
    fun fixesDroppedBeforeARecoveryBelongToTheRecoveredActivity() {
        val original = ActivityRecorder(store)
        original.start("a1", Sport.RUN, 0, 0)
        original.gpsReady(0, 0)
        original.onSample(sample(0, 45.0))

        val restarted = ActivityRecorder(store) // the service came back before anyone recovered the activity
        restarted.onSample(sample(1_000, 45.0))
        restarted.onSample(sample(2_000, 45.0))
        restarted.recover("a1", 3_000, 3_000)
        val diag = restarted.stop(4_000, 4_000).diagnostics

        assertEquals(mapOf(DroppedInputReason.NO_ACTIVE_ACTIVITY to 2), diag.droppedInputs)
    }

    @Test
    fun gapsAreCountedInsideARecordingIntervalAndNeverAcrossAPause() {
        val recorder = ActivityRecorder(store)
        recorder.start("a1", Sport.RUN, 0, 0)
        recorder.gpsReady(0, 0)
        recorder.onSample(sample(0, 45.0))
        recorder.onSample(sample(5_000, 45.0)) // 5 s: not a gap
        recorder.onSample(sample(25_000, 45.0)) // 20 s of silence while recording: a gap
        recorder.pause(26_000, 26_000)
        recorder.resume(200_000, 200_000) // a long pause is not a gap
        recorder.onSample(sample(201_000, 45.0))
        val live = recorder.stop(202_000, 202_000).diagnostics

        assertEquals(SignalGaps(10_000, 1, 20_000, listOf(SignalGap(5_000, 20_000))), live.locationGaps)
        assertEquals(null, live.heartRate)
        // The report rebuilt from the file agrees with the one built live.
        assertEquals(live.locationGaps, store.diagnosticsOf("a1")!!.locationGaps)
    }

    @Test
    fun heartRateDiagnosticsCarryTheStrapNumbersAndItsLongestGap() {
        val recorder = ActivityRecorder(store)
        recorder.start("a1", Sport.RUN, 0, 0)
        recorder.gpsReady(0, 0)
        for (t in listOf(0L, 1_000L, 2_000L, 40_000L, 41_000L)) {
            recorder.onHeartRate(HeartRateSample(t, t, if (t < 40_000L) 120 else 160))
        }
        val live = recorder.stop(42_000, 42_000).diagnostics
        val hr = live.heartRate!!

        assertEquals(136, hr.avgBpm) // (120 * 3 + 160 * 2) / 5
        assertEquals(160, hr.maxBpm)
        assertEquals(1, hr.gaps.count)
        assertEquals(38_000L, hr.gaps.longestMs)
        assertEquals(hr, store.diagnosticsOf("a1")!!.heartRate)
    }

    @Test
    fun aRecoveryEndsTheStreamSoTheDowntimeIsNotAGap() {
        val first = ActivityRecorder(store)
        first.start("a1", Sport.RUN, 0, 0)
        first.gpsReady(0, 0)
        first.onSample(sample(0, 45.0))
        val second = ActivityRecorder(store)
        second.recover("a1", 600_000, 600_000) // killed for ten minutes
        second.onSample(sample(601_000, 45.0))
        val diag = second.stop(602_000, 602_000).diagnostics

        assertEquals(0, diag.locationGaps.count)
        assertEquals(1, diag.recoveryGaps.size)
    }
}
