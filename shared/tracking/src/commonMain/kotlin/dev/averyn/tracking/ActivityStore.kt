package dev.averyn.tracking

import dev.averyn.domain.ActivityState
import dev.averyn.domain.LocationSample
import dev.averyn.domain.Sport
import dev.averyn.domain.StateEvent
import dev.averyn.sensors.HeartRateSample
import dev.averyn.sensors.RrInterval
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.readLine
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The format version new activity files are written with (ADR-0020): GPS samples plus optional `HR ` records.
 * Version 1 is only for files written before the heart-rate channel existed.
 */
const val CURRENT_FORMAT_VERSION = 2

/**
 * The format version of a canonical file produced by the GPX importer (TDD-0008): the same records as version
 * 2, but with a nullable `horizontalAccuracyM`, because a GPX track carries no accuracy.
 */
const val IMPORT_FORMAT_VERSION = 3

/** The format versions this build can read: 1 (GPS only), 2 (GPS + heart rate), 3 (imported). */
val SUPPORTED_FORMAT_VERSIONS = setOf(1, CURRENT_FORMAT_VERSION, IMPORT_FORMAT_VERSION)

/** Static metadata for one activity: the first line of its store file (ADR-0015). */
@Serializable
data class ActivityMetadata(
    val activityId: String,
    val sport: Sport,
    val startedAtMs: Long,
    /**
     * 1 = GPS only (old files, absent field decodes to 1); 2 = GPS + optional `HR ` records (ADR-0020);
     * 3 = an imported canonical file, whose accuracy is null because the source has none (TDD-0008).
     */
    val formatVersion: Int = 1,
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

    /** Heart-rate sample written alongside GPS samples (formatVersion 2, ADR-0020). */
    data class HeartRate(
        val sample: HeartRateSample,
    ) : ActivityRecord
}

private const val META_PREFIX = "META "
private const val SAMPLE_PREFIX = "SAMPLE "
private const val EVENT_PREFIX = "EVENT "
private const val HR_PREFIX = "HR "

/** The file name suffix of the heart-rate sidecar: it ends in `.jsonl` but it is not an activity file. */
private const val RR_FILE_SUFFIX = ".rr.jsonl"

/** The file name suffix of the local summary sidecar (TDD-0011): the numbers of an activity on this device. */
private const val SUMMARY_FILE_SUFFIX = ".summary.json"

// NaN/Infinity in an optional field (altitude, speed, bearing) is stored as the platform reported it rather than
// crashing the recording thread: raw samples are immutable and never silently lost (F5).
private val storeJson =
    Json {
        ignoreUnknownKeys = true
        allowSpecialFloatingPointValues = true
    }

/**
 * Writes a whole activity file in one pass: the `META` line and every record after it, in order, with a single
 * open file.
 *
 * The importer (TDD-0008) canonicalizes a whole parsed track at once; going through [ActivityStore]'s
 * append-one-line-at-a-time API would open and close the file once per sample, which is fine for a device
 * recording at 1 Hz and wasteful for a 10-hour import.
 */
fun writeActivityFile(
    path: Path,
    metadata: ActivityMetadata,
    records: Iterable<ActivityRecord>,
) {
    SystemFileSystem.sink(path, append = false).buffered().use { sink ->
        sink.writeString(META_PREFIX + storeJson.encodeToString(ActivityMetadata.serializer(), metadata) + "\n")
        for (record in records) {
            val line =
                when (record) {
                    is ActivityRecord.Sample ->
                        SAMPLE_PREFIX + storeJson.encodeToString(LocationSample.serializer(), record.sample)
                    is ActivityRecord.Event ->
                        EVENT_PREFIX + storeJson.encodeToString(StateEvent.serializer(), record.event)
                    is ActivityRecord.HeartRate ->
                        HR_PREFIX + storeJson.encodeToString(HeartRateSample.serializer(), record.sample)
                }
            sink.writeString(line + "\n")
        }
    }
}

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
    // NaN/Infinity in an optional field (altitude, speed, bearing) is stored as the platform reported it rather than
    // crashing the recording thread: raw samples are immutable and never silently lost (F5).
    private val json = storeJson

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

        /** Record-type prefix for RR intervals in the sidecar `<id>.rr.jsonl` (ADR-0020/0021). */
        private const val RR_PREFIX = "RR "
    }

    private fun pathFor(activityId: String) = Path(directory, "$activityId.jsonl")

    private fun pathForLocalSummary(activityId: String) = Path(directory, "$activityId$SUMMARY_FILE_SUFFIX")

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

    /** Appends a heart-rate sample written alongside GPS (formatVersion 2, flushed before return — F3). */
    fun appendHeartRate(
        activityId: String,
        sample: HeartRateSample,
    ) = appendLine(activityId, HR_PREFIX + json.encodeToString(HeartRateSample.serializer(), sample))

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
                line.startsWith(HR_PREFIX) ->
                    DecodeResult.Ok(
                        ActivityRecord.HeartRate(
                            json.decodeFromString(HeartRateSample.serializer(), line.removePrefix(HR_PREFIX)),
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
            // The heart-rate sidecar (`<id>.rr.jsonl`) is not an activity of its own: it belongs to one.
            .filter { it.name.endsWith(".jsonl") && !it.name.endsWith(RR_FILE_SUFFIX) }
            .mapNotNull { metadataOf(it.name.removeSuffix(".jsonl")) }

    /**
     * The state after [activityId]'s last EVENT, or null if it has none (or no file).
     *
     * The apps poll this while an activity is being recorded, so it decodes only the EVENT lines (a handful)
     * and merely skips past the SAMPLE lines (one per second, thousands after a long run) instead of parsing
     * every one of them. A corrupt EVENT line is ignored here; `forEachRecord` is where losses are counted.
     */
    fun lastStateOf(activityId: String): ActivityState? {
        val path = pathFor(activityId)
        if (!SystemFileSystem.exists(path)) return null
        var lastState: ActivityState? = null
        SystemFileSystem.source(path).buffered().use { source ->
            source.readLine() // META line
            while (true) {
                val line = source.readLine() ?: break
                if (!line.startsWith(EVENT_PREFIX)) continue
                val decoded = decodeLine(line)
                if (decoded is DecodeResult.Ok && decoded.record is ActivityRecord.Event) {
                    lastState = decoded.record.event.state
                }
            }
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

    /**
     * Throws one activity off the device: the raw file and the local summary sidecar (TDD-0011). The
     * heart-rate sidecar and the sync status have their own lifecycle and are deleted by their owners.
     */
    fun delete(activityId: String) {
        val path = pathFor(activityId)
        if (SystemFileSystem.exists(path)) SystemFileSystem.delete(path)
        deleteLocalSummary(activityId)
    }

    // --- Local summary sidecar (TDD-0011): `<id>.summary.json`, the numbers of an activity on this device.

    /**
     * Writes the local summary of an activity, next to its file.
     *
     * The write is atomic (a temporary file and a rename), so a kill leaves the previous summary, never half
     * a JSON. A summary that cannot be written is a summary that is missing: the activity itself is intact.
     */
    fun writeLocalSummary(summary: LocalActivitySummary) {
        val target = pathForLocalSummary(summary.activityId)
        val temp = Path(directory, "${summary.activityId}$SUMMARY_FILE_SUFFIX.tmp")
        try {
            SystemFileSystem.sink(temp, append = false).buffered().use { sink ->
                sink.writeString(json.encodeToString(LocalActivitySummary.serializer(), summary))
            }
            SystemFileSystem.atomicMove(temp, target)
        } catch (e: Exception) {
            // A cache of derived data must never fail the activity that produced it.
            if (SystemFileSystem.exists(temp)) SystemFileSystem.delete(temp)
        }
    }

    /** The local summary of an activity, or null when there is none (or it cannot be read). */
    fun readLocalSummary(activityId: String): LocalActivitySummary? {
        val path = pathForLocalSummary(activityId)
        if (!SystemFileSystem.exists(path)) return null
        return try {
            json.decodeFromString(
                LocalActivitySummary.serializer(),
                SystemFileSystem.source(path).buffered().use { it.readString() },
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Throws the local summary of an activity away. */
    fun deleteLocalSummary(activityId: String) {
        val path = pathForLocalSummary(activityId)
        if (SystemFileSystem.exists(path)) SystemFileSystem.delete(path)
    }

    /** The size of the files of an activity on this device, in bytes. */
    fun localSize(activityId: String): Long = SystemFileSystem.metadataOrNull(pathFor(activityId))?.size ?: 0L

    /**
     * Writes the local summary of the activities that finished before this build existed (TDD-0011).
     *
     * @return how many summaries were written.
     */
    fun backfillLocalSummaries(limit: Int = 20): Int {
        var written = 0
        for (metadata in list().sortedByDescending { it.startedAtMs }) {
            if (written >= limit) break
            if (readLocalSummary(metadata.activityId) != null) continue
            if (lastStateOf(metadata.activityId) != ActivityState.COMPLETED) continue
            val summary = localSummaryOf(metadata.activityId) ?: continue
            writeLocalSummary(summary)
            written++
        }
        return written
    }

    // --- Heart-rate sidecar (ADR-0020/0021): `<id>.rr.jsonl`, own lifecycle (consent-gated, independently deletable).

    private fun pathForRr(activityId: String) = Path(directory, "$activityId$RR_FILE_SUFFIX")

    /**
     * Creates the sidecar's META line (formatVersion 1) from the activity's own metadata, so the sidecar
     * says which activity it belongs to (ADR-0020). Overwrites an existing sidecar's META: the sidecar's
     * lifecycle is separate, but its header is always the activity it was recorded with.
     */
    fun createRrMetadata(metadata: ActivityMetadata) {
        SystemFileSystem.sink(pathForRr(metadata.activityId), append = false).buffered().use { sink ->
            sink.writeString(
                META_PREFIX +
                    json.encodeToString(
                        ActivityMetadata.serializer(),
                        metadata.copy(formatVersion = 1),
                    ) +
                    "\n",
            )
        }
    }

    /**
     * Creates the sidecar's META line only when the sidecar does not have one yet: the first RR interval of
     * an activity must not land in a file without a header (the reader would not know what it belongs to).
     * Returns true when the header was written.
     */
    fun ensureRrMetadata(metadata: ActivityMetadata): Boolean {
        if (rrMetadataOf(metadata.activityId) != null) return false
        createRrMetadata(metadata)
        return true
    }

    /** Whether the sidecar of [activityId] exists: the caller writes its header before the first interval. */
    fun hasRr(activityId: String): Boolean = SystemFileSystem.exists(pathForRr(activityId))

    /** Appends one raw RR interval to the sidecar (flushed before return — F3). */
    fun appendRrInterval(
        activityId: String,
        rr: RrInterval,
    ) = appendLineRr(activityId, RR_PREFIX + json.encodeToString(RrInterval.serializer(), rr))

    private fun appendLineRr(
        activityId: String,
        line: String,
    ) {
        SystemFileSystem.sink(pathForRr(activityId), append = true).buffered().use { sink ->
            sink.writeString(line)
            sink.writeString("\n")
        }
    }

    /** Sidecar metadata, or null when no sidecar exists. `formatVersion` absent decodes to 1. */
    fun rrMetadataOf(activityId: String): ActivityMetadata? {
        val path = pathForRr(activityId)
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

    /** The whole sidecar of [activityId], exactly as stored: what sync uploads for RR. */
    fun readRrRaw(activityId: String): ByteArray =
        SystemFileSystem.source(pathForRr(activityId)).buffered().use { it.readByteArray() }

    /** Streams every record of the sidecar after the META line, in file order; bounded memory. */
    fun forEachRrRecord(
        activityId: String,
        onLoss: (RecordLossReason) -> Unit = {},
        action: (RrInterval) -> Unit,
    ) {
        val path = pathForRr(activityId)
        if (!SystemFileSystem.exists(path)) return
        SystemFileSystem.source(path).buffered().use { source ->
            source.readLine() // META line
            while (true) {
                val line = source.readLine() ?: break
                if (!line.startsWith(RR_PREFIX)) {
                    onLoss(RecordLossReason.UNKNOWN_RECORD_TYPE)
                    continue
                }
                runCatching {
                    json.decodeFromString(RrInterval.serializer(), line.removePrefix(RR_PREFIX))
                }.onFailure { onLoss(RecordLossReason.TRUNCATED_RECORD) }
                    .onSuccess { action(it) }
            }
        }
    }

    /** Deletes the RR sidecar (consent revocation, ADR-0021). No-op when absent. */
    fun deleteRr(activityId: String) {
        val path = pathForRr(activityId)
        if (SystemFileSystem.exists(path)) SystemFileSystem.delete(path)
    }
}
