package dev.averyn.tracking

import dev.averyn.domain.ActivityState
import dev.averyn.domain.LocationSample
import dev.averyn.domain.Reason
import dev.averyn.domain.Sport
import dev.averyn.domain.StateEvent
import dev.averyn.sensors.HeartRateSample
import dev.averyn.sensors.RrInterval
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

class ActivityStoreTest {
    private lateinit var dir: java.nio.file.Path
    private lateinit var store: ActivityStore

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("averyn-activity-store-test")
        store = ActivityStore(Path(dir.toString()))
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun sample(elapsedMs: Long) = LocationSample(elapsedMs, elapsedMs, 45.0, 9.0, 5.0)

    @Test
    fun lastStateOfReadsOnlyTheEventLinesAndIgnoresDamagedOnes() {
        store.create(ActivityMetadata("a1", Sport.RUN, 0))
        store.appendEvent("a1", StateEvent(0, 0, ActivityState.PREPARING, Reason.USER))
        store.appendEvent("a1", StateEvent(1, 1, ActivityState.RECORDING, Reason.GPS_READY))
        repeat(50) { store.appendSample("a1", sample(100L + it)) }
        store.appendEvent("a1", StateEvent(200, 200, ActivityState.PAUSED, Reason.USER))
        repeat(5) { store.appendSample("a1", sample(300L + it)) }
        val path = Path(dir.toString(), "a1.jsonl")
        SystemFileSystem.sink(path, append = true).buffered().use { it.writeString("EVENT {\"elapsedRe") }

        assertEquals(ActivityState.PAUSED, store.lastStateOf("a1"))
        assertEquals(null, store.lastStateOf("missing"))
    }

    @Test
    fun roundTripsSamplesAndEventsInOrder() {
        store.create(ActivityMetadata("a1", Sport.RUN, 1_000))
        store.appendEvent("a1", StateEvent(0, 1_000, ActivityState.PREPARING, Reason.USER))
        store.appendSample("a1", sample(100))
        store.appendSample("a1", sample(200))
        store.appendEvent("a1", StateEvent(200, 1_200, ActivityState.RECORDING, Reason.GPS_READY))

        val records = mutableListOf<ActivityRecord>()
        store.forEachRecord("a1") { records += it }

        assertEquals(4, records.size)
        assertEquals(sample(100), (records[1] as ActivityRecord.Sample).sample)
        assertEquals(ActivityState.RECORDING, (records[3] as ActivityRecord.Event).event.state)
    }

    @Test
    fun metadataOfReadsWithoutScanningRecords() {
        store.create(ActivityMetadata("a1", Sport.RIDE, 5_000))
        store.appendSample("a1", sample(0))
        assertEquals(ActivityMetadata("a1", Sport.RIDE, 5_000), store.metadataOf("a1"))
    }

    @Test
    fun truncatedTrailingLineIsCountedNotSilentlyDropped() {
        store.create(ActivityMetadata("a1", Sport.RUN, 0))
        store.appendSample("a1", sample(0))
        // Simulate a kill mid-write: append a syntactically incomplete SAMPLE line with no trailing newline.
        val path = Path(dir.toString(), "a1.jsonl")
        SystemFileSystem.sink(path, append = true).buffered().use { it.writeString("SAMPLE {\"timeMs\":1,\"elapsedRe") }

        val records = mutableListOf<ActivityRecord>()
        val losses = mutableListOf<RecordLossReason>()
        store.forEachRecord("a1", onLoss = { losses += it }) { records += it }

        assertEquals(1, records.size) // the good sample is still read back
        assertEquals(listOf(RecordLossReason.TRUNCATED_RECORD), losses)
    }

    @Test
    fun aCompleteLineThatBreaksTheModelIsAnInvalidValueNotATruncatedLine() {
        store.create(ActivityMetadata("a1", Sport.RUN, 0))
        store.appendSample("a1", sample(0))
        val path = Path(dir.toString(), "a1.jsonl")
        SystemFileSystem.sink(path, append = true).buffered().use {
            // Complete JSON, newline included: a latitude of 123 and a heart rate of 500 are not places or beats.
            it.writeString(
                "SAMPLE {\"timeMs\":2,\"elapsedRealtimeMs\":2,\"latitude\":123.0,\"longitude\":0.0," +
                    "\"horizontalAccuracyM\":5.0}\n",
            )
            it.writeString("HR {\"timeMs\":3,\"elapsedRealtimeMs\":3,\"bpm\":500}\n")
        }

        val records = mutableListOf<ActivityRecord>()
        val losses = mutableListOf<RecordLossReason>()
        store.forEachRecord("a1", onLoss = { losses += it }) { records += it }

        // Whatever the serialization library throws for a value that breaks the model, the answer is the same:
        // counted, with its own reason, and the good records around it are still read.
        assertEquals(1, records.size)
        assertEquals(listOf(RecordLossReason.INVALID_VALUE, RecordLossReason.INVALID_VALUE), losses)
    }

    @Test
    fun interruptedFindsActivitiesLeftRecordingOrPausedOrStopping() {
        store.create(ActivityMetadata("recording", Sport.RUN, 0))
        store.appendEvent("recording", StateEvent(0, 0, ActivityState.RECORDING, Reason.GPS_READY))

        store.create(ActivityMetadata("completed", Sport.RUN, 0))
        store.appendEvent("completed", StateEvent(0, 0, ActivityState.RECORDING, Reason.GPS_READY))
        store.appendEvent("completed", StateEvent(1, 1, ActivityState.STOPPING, Reason.USER))
        store.appendEvent("completed", StateEvent(1, 1, ActivityState.COMPLETED, Reason.USER))

        val interruptedIds = store.interrupted().map { it.activityId }.toSet()
        assertEquals(setOf("recording"), interruptedIds)
    }

    @Test
    fun eventWithoutBatteryPercentDecodesFromAnOlderStoreFormat() {
        store.create(ActivityMetadata("a1", Sport.RUN, 0))
        // No "batteryPercent" key: what a pre-diagnostics-v1 app wrote. ignoreUnknownKeys handles new fields
        // added later; the default value here must handle an *older* file missing a newer field.
        val path = Path(dir.toString(), "a1.jsonl")
        SystemFileSystem.sink(path, append = true).buffered().use {
            it.writeString(
                "EVENT {\"elapsedRealtimeMs\":0,\"timeMs\":0,\"state\":\"RECORDING\",\"reason\":\"GPS_READY\"}\n",
            )
        }

        val records = mutableListOf<ActivityRecord>()
        store.forEachRecord("a1") { records += it }

        assertEquals(null, (records.single() as ActivityRecord.Event).event.batteryPercent)
    }

    @Test
    fun listReturnsEveryStoredActivity() {
        store.create(ActivityMetadata("a1", Sport.WALK, 0))
        store.create(ActivityMetadata("a2", Sport.HIKE, 0))
        assertEquals(setOf("a1", "a2"), store.list().map { it.activityId }.toSet())
    }

    @Test
    fun deleteRemovesTheFile() {
        store.create(ActivityMetadata("a1", Sport.RUN, 0))
        store.delete("a1")
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun metadataDefaultsToFormatVersion1AndRoundTripsWith2() {
        // Old-style metadata without formatVersion decodes to 1.
        store.create(ActivityMetadata("a1", Sport.RUN, 0))
        assertEquals(1, store.metadataOf("a1")!!.formatVersion)

        // New-style metadata is written and read back with formatVersion 2.
        store.create(ActivityMetadata("a2", Sport.RIDE, 1_000, formatVersion = 2))
        assertEquals(2, store.metadataOf("a2")!!.formatVersion)
    }

    @Test
    fun heartRateRecordsRoundTripThroughForEachRecord() {
        store.create(ActivityMetadata("a1", Sport.RUN, 0, formatVersion = 2))
        store.appendHeartRate("a1", HeartRateSample(1000, 1000, 137, true))
        store.appendHeartRate("a1", HeartRateSample(2000, 2000, 142, false))

        val hrSamples = mutableListOf<HeartRateSample>()
        val losses = mutableListOf<RecordLossReason>()
        store.forEachRecord("a1", onLoss = { losses += it }) { record ->
            if (record is ActivityRecord.HeartRate) hrSamples += record.sample
        }

        assertEquals(listOf(HeartRateSample(1000, 1000, 137, true), HeartRateSample(2000, 2000, 142, false)), hrSamples)
        assertTrue(losses.isEmpty())
    }

    @Test
    fun unknownRecordKindIsCountedAsLossNotACrash() {
        store.create(ActivityMetadata("a1", Sport.RUN, 0))
        val path = Path(dir.toString(), "a1.jsonl")
        SystemFileSystem.sink(path, append = true).buffered().use {
            it.writeString("HR {\"bogus\":true}\n") // not a valid HeartRateSample JSON
        }

        val losses = mutableListOf<RecordLossReason>()
        store.forEachRecord("a1", onLoss = { losses += it }) { }

        assertEquals(listOf(RecordLossReason.TRUNCATED_RECORD), losses)
    }

    @Test
    fun rrSidecarRoundTrip() {
        store.createRrMetadata(ActivityMetadata("a1", Sport.RUN, 0))
        store.appendRrInterval("a1", RrInterval(1000, 1000, 857))
        store.appendRrInterval("a1", RrInterval(2000, 2000, 800))

        val rrs = mutableListOf<RrInterval>()
        val losses = mutableListOf<RecordLossReason>()
        store.forEachRrRecord("a1", onLoss = { losses += it }) { rrs += it }

        assertEquals(listOf(RrInterval(1000, 1000, 857), RrInterval(2000, 2000, 800)), rrs)
        assertTrue(losses.isEmpty())
        assertEquals(ActivityMetadata("a1", Sport.RUN, 0, formatVersion = RR_FORMAT_VERSION), store.rrMetadataOf("a1"))
    }

    @Test
    fun rrSidecarHeaderIsTheActivityItBelongsTo() {
        val metadata = ActivityMetadata("a1", Sport.RIDE, 12_345, formatVersion = 2)
        store.createRrMetadata(metadata)

        // The sidecar has its own format version, but it says which activity it was recorded with.
        assertEquals(
            ActivityMetadata("a1", Sport.RIDE, 12_345, formatVersion = RR_FORMAT_VERSION),
            store.rrMetadataOf("a1"),
        )
    }

    @Test
    fun ensureRrMetadataWritesTheHeaderOnlyOnce() {
        val metadata = ActivityMetadata("a1", Sport.RUN, 0)

        assertTrue(store.ensureRrMetadata(metadata))
        assertFalse(store.ensureRrMetadata(metadata))

        store.appendRrInterval("a1", RrInterval(1000, 1000, 857))
        val rrs = mutableListOf<RrInterval>()
        store.forEachRrRecord("a1") { rrs += it }

        assertEquals(listOf(RrInterval(1000, 1000, 857)), rrs)
        assertEquals(ActivityMetadata("a1", Sport.RUN, 0, formatVersion = RR_FORMAT_VERSION), store.rrMetadataOf("a1"))
    }

    @Test
    fun theSidecarIsNotAnActivityOfItsOwn() {
        store.create(ActivityMetadata("a1", Sport.RUN, 0))
        store.createRrMetadata(ActivityMetadata("a1", Sport.RUN, 0))
        store.appendRrInterval("a1", RrInterval(1000, 1000, 857))

        // The sidecar is a file of the activity, not another activity: the list holds it once.
        assertEquals(listOf("a1"), store.list().map { it.activityId })
    }

    @Test
    fun rrSidecarIsDeletedIndependently() {
        store.createRrMetadata(ActivityMetadata("a1", Sport.RUN, 0))
        store.appendRrInterval("a1", RrInterval(1000, 1000, 857))
        store.deleteRr("a1")

        assertEquals(null, store.rrMetadataOf("a1"))
        // The main activity file is untouched.
        store.create(ActivityMetadata("a1", Sport.RUN, 0))
        assertEquals(1, store.metadataOf("a1")!!.formatVersion)
    }
}
