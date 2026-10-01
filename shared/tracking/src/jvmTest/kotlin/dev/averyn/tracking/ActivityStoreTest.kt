package dev.averyn.tracking

import dev.averyn.domain.ActivityState
import dev.averyn.domain.LocationSample
import dev.averyn.domain.Reason
import dev.averyn.domain.Sport
import dev.averyn.domain.StateEvent
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
