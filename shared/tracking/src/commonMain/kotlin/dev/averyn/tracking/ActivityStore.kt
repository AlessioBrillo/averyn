package dev.averyn.tracking

import dev.averyn.domain.ActivityState
import dev.averyn.domain.LocationSample
import dev.averyn.domain.Sport
import dev.averyn.domain.StateEvent
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.readLine
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Static metadata for one activity: the first line of its store file (ADR-0015). */
@Serializable
data class ActivityMetadata(
    val activityId: String,
    val sport: Sport,
    val startedAtMs: Long,
)

/** Why a stored line wasn't usable (TDD-0001 F5: every drop is counted with a reason, never silent). */
enum class RecordLossReason {
    /** The line's JSON was incomplete — the most likely cause is a kill mid-write of the last line. */
    TRUNCATED_RECORD,

    /** The line didn't start with a recognized record-type tag. */
    UNKNOWN_RECORD_TYPE,
}

sealed interface ActivityRecord {
    data class Sample(
        val sample: LocationSample,
    ) : ActivityRecord

    data class Event(
        val event: StateEvent,
    ) : ActivityRecord
}

private const val META_PREFIX = "META "
private const val SAMPLE_PREFIX = "SAMPLE "
private const val EVENT_PREFIX = "EVENT "

/**
 * Durable, append-only local store (ADR-0015): one JSON Lines file per activity (`<activityId>.jsonl` under
 * [directory]), first line `META <ActivityMetadata>`, then one `SAMPLE`/`EVENT` record per line. Every
 * [appendSample]/[appendEvent] call writes and flushes before returning (TDD-0001 F3) — there is no
 * batching, so the caller only uses a sample for live metrics after the store call that persisted it returns.
 *
 * ponytail: opens and closes the file on every single append rather than holding a sink open for the
 * activity's lifetime. Simpler (no writer-lifecycle state to leak across process death) and cheap enough at
 * a ~1/s sample rate; revisit only if profiling on a real device shows this file-open overhead matters.
 */
class ActivityStore(
    val directory: Path,
) {
    private val json = Json { ignoreUnknownKeys = true }

    init {
        if (!SystemFileSystem.exists(directory)) SystemFileSystem.createDirectories(directory)
    }

    companion object {
        /**
         * Convenience for native adapters that don't otherwise need kotlinx-io types on their side of the
         * FFI boundary (Swift/iOS): a plain directory path in, an [ActivityStore] out. From Swift:
         * `ActivityStore.companion.open(directoryPath:)` — a top-level function here isn't reliably
         * callable from Swift, but a companion object member is.
         */
        fun open(directoryPath: String): ActivityStore = ActivityStore(Path(directoryPath))
    }

    private fun pathFor(activityId: String) = Path(directory, "$activityId.jsonl")

    fun create(metadata: ActivityMetadata) {
        SystemFileSystem.sink(pathFor(metadata.activityId), append = false).buffered().use { sink ->
            sink.writeString(META_PREFIX + json.encodeToString(ActivityMetadata.serializer(), metadata) + "\n")
        }
    }

    fun appendSample(
        activityId: String,
        sample: LocationSample,
    ) = appendLine(activityId, SAMPLE_PREFIX + json.encodeToString(LocationSample.serializer(), sample))

    fun appendEvent(
        activityId: String,
        event: StateEvent,
    ) = appendLine(activityId, EVENT_PREFIX + json.encodeToString(StateEvent.serializer(), event))

    private fun appendLine(
        activityId: String,
        line: String,
    ) {
        SystemFileSystem.sink(pathFor(activityId), append = true).buffered().use { sink ->
            sink.writeString(line)
            sink.writeString("\n")
        } // close() flushes; no fsync beyond the OS page cache (ADR-0015's accepted gap for hard power loss).
    }

    // ponytail: a kill exactly mid-write of the META line (the file's very first write) makes this return
    // null, and list()/interrupted() then silently skip the whole activity via mapNotNull — unlike a
    // corrupt SAMPLE/EVENT line, which forEachRecord's onLoss still counts. Narrower exposure than any other
    // single write (there's only ever one META write per activity, vs. potentially thousands of samples),
    // and the same class of gap ADR-0015 already accepts for hard power loss; a real fix needs a way to
    // surface an "orphaned activity file" that isn't just a null metadata, which no caller needs yet.
    fun metadataOf(activityId: String): ActivityMetadata? {
        val path = pathFor(activityId)
        if (!SystemFileSystem.exists(path)) return null
        return SystemFileSystem.source(path).buffered().use { source ->
            val line = source.readLine() ?: return@use null
            if (!line.startsWith(META_PREFIX)) return@use null
            try {
                json.decodeFromString(ActivityMetadata.serializer(), line.removePrefix(META_PREFIX))
            } catch (e: SerializationException) {
                null
            }
        }
    }

    /**
     * The whole file of [activityId], exactly as stored: what sync uploads (TDD-0002).
     *
     * ponytail: read into memory (10 h of 1 Hz samples is about 7 MB). Stream it if activities ever get much larger.
     */
    fun readRaw(activityId: String): ByteArray =
        SystemFileSystem.source(pathFor(activityId)).buffered().use { it.readByteArray() }

    /** Streams every record of [activityId] after the META line, in file order; bounded memory. */
    fun forEachRecord(
        activityId: String,
        onLoss: (RecordLossReason) -> Unit = {},
        action: (ActivityRecord) -> Unit,
    ) {
        val path = pathFor(activityId)
        if (!SystemFileSystem.exists(path)) return
        SystemFileSystem.source(path).buffered().use { source ->
            source.readLine() // META line, already available via metadataOf()
            while (true) {
                val line = source.readLine() ?: break
                when (val decoded = decodeLine(line)) {
                    is DecodeResult.Ok -> action(decoded.record)
                    is DecodeResult.Loss -> onLoss(decoded.reason)
                }
            }
        }
    }

    private sealed interface DecodeResult {
        data class Ok(
            val record: ActivityRecord,
        ) : DecodeResult

        data class Loss(
            val reason: RecordLossReason,
        ) : DecodeResult
    }

    private fun decodeLine(line: String): DecodeResult =
        try {
            when {
                line.startsWith(SAMPLE_PREFIX) ->
                    DecodeResult.Ok(
                        ActivityRecord.Sample(
                            json.decodeFromString(LocationSample.serializer(), line.removePrefix(SAMPLE_PREFIX)),
                        ),
                    )
                line.startsWith(EVENT_PREFIX) ->
                    DecodeResult.Ok(
                        ActivityRecord.Event(
                            json.decodeFromString(StateEvent.serializer(), line.removePrefix(EVENT_PREFIX)),
                        ),
                    )
                else -> DecodeResult.Loss(RecordLossReason.UNKNOWN_RECORD_TYPE)
            }
        } catch (e: SerializationException) {
            DecodeResult.Loss(RecordLossReason.TRUNCATED_RECORD)
        }

    fun list(): List<ActivityMetadata> =
        SystemFileSystem
            .list(directory)
            .filter { it.name.endsWith(".jsonl") }
            .mapNotNull { metadataOf(it.name.removeSuffix(".jsonl")) }

    /** The state after [activityId]'s last EVENT, or null if it has none (or no file). */
    fun lastStateOf(activityId: String): ActivityState? {
        var lastState: ActivityState? = null
        forEachRecord(activityId) { record ->
            if (record is ActivityRecord.Event) lastState = record.event.state
        }
        return lastState
    }

    /** Activities whose last EVENT left them RECORDING/PAUSED/STOPPING without a clean end (TDD-0001 F4). */
    fun interrupted(): List<ActivityMetadata> =
        list().filter { metadata ->
            val lastState = lastStateOf(metadata.activityId)
            lastState == ActivityState.RECORDING ||
                lastState == ActivityState.PAUSED ||
                lastState == ActivityState.STOPPING
        }

    fun delete(activityId: String) {
        val path = pathFor(activityId)
        if (SystemFileSystem.exists(path)) SystemFileSystem.delete(path)
    }
}
