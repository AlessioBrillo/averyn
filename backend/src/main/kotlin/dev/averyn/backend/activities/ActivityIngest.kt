package dev.averyn.backend.activities

import dev.averyn.analysis.ActivityAnalysis
import dev.averyn.api.ActivityPage
import dev.averyn.api.ActivitySummary
import dev.averyn.backend.storage.RawObjectStore
import dev.averyn.domain.ActivityState
import dev.averyn.domain.LocationSample
import dev.averyn.metrics.DISTANCE_ALGORITHM_VERSION
import dev.averyn.metrics.SPEED_ALGORITHM_VERSION
import dev.averyn.metrics.TIME_ALGORITHM_VERSION
import dev.averyn.sensors.HR_ALGORITHM_VERSION
import dev.averyn.sensors.HeartRateMetrics
import dev.averyn.sensors.HeartRateSample
import dev.averyn.tracking.ActivityMetadata
import dev.averyn.tracking.ActivityStore
import dev.averyn.tracking.RecordLossReason
import dev.averyn.tracking.ReplayResult
import dev.averyn.tracking.SUPPORTED_FORMAT_VERSIONS
import dev.averyn.tracking.replay
import kotlinx.io.files.Path
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.io.path.name

/** Where an activity comes from: recorded by a device, or imported from a file (TDD-0008). */
const val SOURCE_DEVICE = "device"

/** The content type of an imported GPX file in the object store. */
const val GPX_CONTENT_TYPE = "application/gpx+xml"

/** Metric algorithms an activity row was computed with (a change of any of them is a new version, never an edit). */
const val METRICS_ALGORITHM_VERSION = "$DISTANCE_ALGORITHM_VERSION,$TIME_ALGORITHM_VERSION,$SPEED_ALGORITHM_VERSION"

/** Keyset position `(started_at, id)` of the last activity of a page, as `"<instant>_<uuid>"`. */
data class ActivityCursor(
    val startedAt: Instant,
    val id: UUID,
) {
    override fun toString() = "${startedAt}_$id"

    companion object {
        fun parseOrNull(text: String): ActivityCursor? =
            runCatching {
                ActivityCursor(Instant.parse(text.substringBefore('_')), UUID.fromString(text.substringAfter('_')))
            }.getOrNull()
    }
}

sealed interface IngestResult {
    data class Created(
        val summary: ActivitySummary,
    ) : IngestResult

    /** The same bytes were uploaded before: an idempotent retry. */
    data class Existing(
        val summary: ActivitySummary,
    ) : IngestResult

    /** The same activity id was uploaded before with different bytes. */
    data object Conflict : IngestResult

    data class Invalid(
        val reason: String,
    ) : IngestResult

    /** Over the upload limit; produced by the route while reading the body, never by [ActivityIngest]. */
    data object TooLarge : IngestResult

    /** The owner deleted this activity id (ADR-0017): it is not accepted again. */
    data object Gone : IngestResult

    /** The account was deleted (ADR-0017), possibly while this very upload was being processed. */
    data object AccountDeleted : IngestResult

    /** Storing the file would take the owner over their storage quota (TDD-0005 §5.5); nothing was stored. */
    data object QuotaExceeded : IngestResult
}

/** Raw bytes one user may store (TDD-0005 §5.5): about 30 files at the upload limit, far more real activities. */
const val DEFAULT_USER_QUOTA_BYTES = 1024L * 1024 * 1024

/**
 * Turns an uploaded raw activity file into an `activities` row (TDD-0002 §5.2): replay it with the shared device
 * code, store the file byte-for-byte, record the derived summary. Blocking (JDBC, S3): call from `Dispatchers.IO`.
 */
class ActivityIngest(
    private val db: DataSource,
    private val raw: RawObjectStore,
    private val quotaBytes: Long = DEFAULT_USER_QUOTA_BYTES,
    private val analysisStore: ActivityAnalysisStore = ActivityAnalysisStore(db),
) {
    private val log = LoggerFactory.getLogger(ActivityIngest::class.java)

    /**
     * @param file the uploaded body, already on disk and named `<clientActivityId>.jsonl` (the store layout)
     * @param sha256 lower-case hex SHA-256 of that file
     */
    fun ingest(
        ownerId: UUID,
        clientActivityId: UUID,
        file: java.nio.file.Path,
        sha256: String,
    ): IngestResult = ingestInternal(ownerId, clientActivityId, file, sha256, null, null, SOURCE_DEVICE, null)

    /**
     * The same as [ingest], for a file that was imported (TDD-0008): the canonical file is stored as the raw
     * activity, the file it was imported from is stored next to it, and the row says where it came from.
     */
    fun ingestImported(
        ownerId: UUID,
        clientActivityId: UUID,
        canonicalFile: java.nio.file.Path,
        canonicalSha256: String,
        originalFile: java.nio.file.Path,
        originalSha256: String,
        source: String,
        importVersion: String,
    ): IngestResult =
        ingestInternal(
            ownerId,
            clientActivityId,
            canonicalFile,
            canonicalSha256,
            originalFile,
            originalSha256,
            source,
            importVersion,
        )

    private fun ingestInternal(
        ownerId: UUID,
        clientActivityId: UUID,
        file: java.nio.file.Path,
        sha256: String,
        originalFile: java.nio.file.Path?,
        originalSha256: String?,
        source: String,
        importVersion: String?,
    ): IngestResult {
        if (wasDeleted(ownerId, clientActivityId)) return IngestResult.Gone
        existing(ownerId, clientActivityId)?.let { return resolve(it, sha256) }
        // ponytail: check, then insert, not serialized: one user's concurrent uploads can overshoot the quota by up to
        // MAX_CONCURRENT_UPLOADS files. Lock the user row around put + insert if the quota must ever be exact.
        val sourceBytes = originalFile?.let { Files.size(it) } ?: 0L
        if (storedBytes(ownerId) + Files.size(file) + sourceBytes > quotaBytes) return IngestResult.QuotaExceeded

        val store = ActivityStore(Path(file.parent.toString()))
        val activityId = file.name.removeSuffix(".jsonl")
        // One line per continuous recording interval: a pause ends a line, it does not bridge it.
        val input = AnalysisInput()
        val metadata: ActivityMetadata
        val replayed: ReplayResult
        val hrSamples = mutableListOf<Int>()
        try {
            metadata = store.metadataOf(activityId) ?: return IngestResult.Invalid("no activity metadata line")
            if (!metadata.activityId.equals(clientActivityId.toString(), ignoreCase = true)) {
                return IngestResult.Invalid("activity id does not match the file's metadata")
            }
            if (metadata.formatVersion !in SUPPORTED_FORMAT_VERSIONS) {
                return IngestResult.Invalid("unsupported activity file format")
            }
            replayed =
                replay(
                    store,
                    activityId,
                    onMeteredSample = { sample: LocationSample -> input.onSample(sample) },
                    onRecordingStart = { input.onRecordingStart() },
                    onHeartRate = { sample: HeartRateSample, state: ActivityState ->
                        input.onHeartRate(sample, state)
                        if (state == ActivityState.RECORDING) hrSamples += sample.bpm
                    },
                )
        } catch (e: RuntimeException) {
            // A line that parses but violates the model (e.g. latitude out of range) is an invalid upload.
            return IngestResult.Invalid("unreadable activity file")
        }
        // A line that is complete JSON but breaks the model is not a cut line (those are counted and the upload is
        // kept): the file was damaged or written by hand, and it is refused (#80).
        if ((replayed.recordLosses[RecordLossReason.INVALID_VALUE] ?: 0) > 0) {
            return IngestResult.Invalid("unreadable activity file")
        }
        if (replayed.lastState != ActivityState.COMPLETED && replayed.lastState != ActivityState.FAILED) {
            return IngestResult.Invalid("activity is not finished (state ${replayed.lastState})")
        }

        // The analysis comes out of the same replay: the splits, the elevation and the series cost no second
        // pass over the file. It is derived data, though: a failure of the analyzer never loses the upload,
        // and the analysis is computed the first time the activity is read.
        val analysis: ActivityAnalysis? =
            try {
                input.build(replayed.metadata.sport, replayed.metrics.snapshot())
            } catch (e: RuntimeException) {
                log.warn("could not analyse the activity {}: {}", clientActivityId, e.javaClass.simpleName)
                null
            }
        // The heart rate of the summary: the same samples, counted by the same metric, whatever the analyzer
        // did. The coverage is over the recording time, the pauses excluded (hr-v2).
        val metrics = replayed.metrics.snapshot()
        val heartRate =
            HeartRateMetrics()
                .apply { hrSamples.forEach { onSample(it) } }
                .snapshot((metrics.elapsedMs - metrics.pausedMs).coerceAtLeast(0))

        // Object first, row second: a failed insert leaves an orphan object, never a row without its raw file.
        // The hash in the key means two different uploads of one id can never overwrite each other's bytes.
        val key = "raw/$ownerId/$clientActivityId/$sha256.jsonl"
        raw.put(key, file)
        val sourceKey =
            if (originalFile != null && originalSha256 != null) {
                "raw/$ownerId/$clientActivityId/source/$originalSha256.gpx".also {
                    raw.put(
                        it,
                        originalFile,
                        GPX_CONTENT_TYPE,
                    )
                }
            } else {
                null
            }

        val snapshot = replayed.metrics.snapshot()
        val quality = replayed.quality.build()
        val trackSegments =
            input.recordedSegments
                .filter { it.size >= 2 }
                .map { segment -> segment.map { "${it.longitude} ${it.latitude}" } }
        val wkt =
            if (trackSegments.isEmpty()) {
                null
            } else {
                "MULTILINESTRING(${trackSegments.joinToString(",") { segment -> "(${segment.joinToString(",")})" }})"
            }
        val inserted =
            try {
                db.connection.use { conn ->
                    conn
                        .prepareStatement(
                            // The two NOT EXISTS make deletion win a race with this upload: the owner's account flag and
                            // the activity tombstone are read in the same statement that inserts, not once at the start
                            // of a request that can run for minutes.
                            """
                            INSERT INTO activities (owner_id, client_activity_id, sport, started_at, final_state,
                                raw_object_key, raw_sha256, raw_size_bytes, distance_m, elapsed_ms, moving_ms, paused_ms,
                                stationary_ms, average_speed_mps, pace_sec_per_km, quality_grade, dropped_records,
                                metrics_algorithm_version, quality_algorithm_version, track,
                                avg_hr_bpm, max_hr_bpm, hr_source, hr_algorithm_version,
                                source, source_object_key, source_sha256, source_size_bytes, import_version, imported_at)
                            SELECT ?::uuid, ?::uuid, ?::text, ?::timestamptz, ?::text, ?::text, ?::text, ?::bigint,
                                ?::double precision, ?::bigint, ?::bigint, ?::bigint, ?::bigint, ?::double precision,
                                ?::double precision, ?::text, ?::integer, ?::text, ?::text,
                                ST_GeomFromText(?::text, 4326),
                                ?::integer, ?::integer, ?::text, ?::text,
                                ?::text, ?::text, ?::text, ?::bigint, ?::text, ?::timestamptz
                            WHERE NOT EXISTS (SELECT 1 FROM users WHERE id = ?::uuid AND deleted_at IS NOT NULL)
                                AND NOT EXISTS (
                                    SELECT 1 FROM deleted_activities
                                    WHERE owner_id = ?::uuid AND client_activity_id = ?::uuid
                                )
                            ON CONFLICT (owner_id, client_activity_id) DO NOTHING
                            RETURNING $SUMMARY_COLUMNS
                            """.trimIndent(),
                        ).use { stmt ->
                            var i = 0
                            stmt.setObject(++i, ownerId)
                            stmt.setObject(++i, clientActivityId)
                            stmt.setString(++i, metadata.sport.name)
                            stmt.setTimestamp(++i, Timestamp.from(Instant.ofEpochMilli(metadata.startedAtMs)))
                            stmt.setString(++i, replayed.lastState.name)
                            stmt.setString(++i, key)
                            stmt.setString(++i, sha256)
                            stmt.setLong(++i, Files.size(file))
                            stmt.setDouble(++i, snapshot.distanceM)
                            stmt.setLong(++i, snapshot.elapsedMs)
                            stmt.setLong(++i, snapshot.movingMs)
                            stmt.setLong(++i, snapshot.pausedMs)
                            stmt.setLong(++i, snapshot.stationaryMs)
                            stmt.setDouble(++i, snapshot.averageSpeedMps)
                            stmt.setObject(++i, snapshot.paceSecPerKm, java.sql.Types.DOUBLE)
                            stmt.setString(++i, quality.grade.name)
                            stmt.setInt(++i, replayed.recordLosses.values.sum())
                            stmt.setString(++i, METRICS_ALGORITHM_VERSION)
                            stmt.setString(++i, quality.algorithmVersion)
                            stmt.setString(++i, wkt)
                            // Which sensor produced the heart rate: the device's BLE strap, or the file the
                            // activity was imported from (ADR-0014: imported metrics stay imported).
                            val hrSource =
                                if (hrSamples.isEmpty()) {
                                    "none"
                                } else if (source == SOURCE_DEVICE) {
                                    "ble-hr"
                                } else {
                                    source
                                }
                            stmt.setInt(++i, heartRate.avgHrBpm)
                            stmt.setInt(++i, heartRate.maxHrBpm)
                            stmt.setString(++i, hrSource)
                            stmt.setString(++i, HR_ALGORITHM_VERSION)
                            stmt.setString(++i, source)
                            stmt.setString(++i, sourceKey)
                            stmt.setString(++i, originalSha256)
                            stmt.setLong(++i, sourceBytes)
                            stmt.setString(++i, importVersion)
                            stmt.setTimestamp(++i, if (sourceKey == null) null else Timestamp.from(Instant.now()))
                            stmt.setObject(++i, ownerId)
                            stmt.setObject(++i, ownerId)
                            stmt.setObject(++i, clientActivityId)
                            stmt.executeQuery().use { rs -> if (rs.next()) rs.toSummary() else null }
                        }
                }
            } catch (e: SQLException) {
                // The same file imported twice at the same time: the unique index on the source hash decided.
                if (e.sqlState != "23505" || originalSha256 == null) throw e
                val winner = existingBySource(ownerId, originalSha256)
                if (winner == null) throw e
                raw.delete(key)
                sourceKey?.let { raw.delete(it) }
                return IngestResult.Existing(winner)
            }
        if (inserted != null) {
            // The analysis is derived data: the activity is valid even when it cannot be stored, and the next
            // read of it computes the analysis on the spot.
            if (analysis != null) {
                try {
                    analysisStore.save(UUID.fromString(inserted.id), analysis)
                } catch (e: Exception) {
                    log.warn("could not store the analysis of activity {}: {}", inserted.id, e.javaClass.simpleName)
                }
            }
            return IngestResult.Created(inserted)
        }
        // No row back: either a concurrent upload of this id won the race between our check and our insert, or the
        // account/activity was deleted meanwhile and the insert refused. In the second case the object just stored
        // can never be referenced by a row, so it goes (an identical concurrent upload would have been `existing`).
        existing(ownerId, clientActivityId)?.let { return resolve(it, sha256) }
        raw.delete(key)
        sourceKey?.let { raw.delete(it) }
        return if (accountDeleted(ownerId)) IngestResult.AccountDeleted else IngestResult.Gone
    }

    private fun accountDeleted(ownerId: UUID): Boolean =
        db.connection.use { conn ->
            conn.prepareStatement("SELECT deleted_at IS NOT NULL FROM users WHERE id = ?").use { stmt ->
                stmt.setObject(1, ownerId)
                stmt.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
            }
        }

    /**
     * The sidecar of the activity, if it has one: its key and its size. A sidecar that replaces another one leaves
     * the old object behind, and the old size must not be counted twice against the quota.
     */
    fun rrSidecar(
        ownerId: UUID,
        clientActivityId: UUID,
    ): Pair<String, Long>? =
        db.connection.use { conn ->
            conn
                .prepareStatement(
                    "SELECT rr_object_key, rr_size_bytes FROM activities " +
                        "WHERE owner_id = ? AND client_activity_id = ? AND rr_object_key IS NOT NULL",
                ).use { stmt ->
                    stmt.setObject(1, ownerId)
                    stmt.setObject(2, clientActivityId)
                    stmt.executeQuery().use { rs ->
                        if (!rs.next()) {
                            null
                        } else {
                            rs.getString(1) to rs.getLong(2)
                        }
                    }
                }
        }

    /**
     * The bytes the owner stores: every canonical file, every imported original, every sidecar. The sidecar
     * counts: it is a file of the user like the others, and leaving it out of the check let an owner store
     * more than the quota through the heart-rate channel.
     */
    private fun storedBytes(ownerId: UUID): Long =
        db.connection.use { conn ->
            conn
                .prepareStatement(
                    "SELECT coalesce(sum(raw_size_bytes + coalesce(source_size_bytes, 0) " +
                        "+ coalesce(rr_size_bytes, 0)), 0) FROM activities WHERE owner_id = ?",
                ).use {
                    it.setObject(1, ownerId)
                    it.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0 }
                }
        }

    /**
     * The activity imported from the file with this hash, or null. The same file is imported once per owner
     * (TDD-0008): the second import returns the first one instead of storing it again.
     */
    fun existingBySource(
        ownerId: UUID,
        sourceSha256: String,
    ): ActivitySummary? =
        db.connection.use { conn ->
            conn
                .prepareStatement("SELECT $SUMMARY_COLUMNS FROM activities WHERE owner_id = ? AND source_sha256 = ?")
                .use { stmt ->
                    stmt.setObject(1, ownerId)
                    stmt.setString(2, sourceSha256)
                    stmt.executeQuery().use { rs -> if (rs.next()) rs.toSummary() else null }
                }
        }

    /** The caller's activity by its server id, or null: also when it belongs to someone else (never a 403). */
    fun find(
        ownerId: UUID,
        id: UUID,
    ): ActivitySummary? =
        db.connection.use { conn ->
            conn.prepareStatement("SELECT $SUMMARY_COLUMNS FROM activities WHERE id = ? AND owner_id = ?").use { stmt ->
                stmt.setObject(1, id)
                stmt.setObject(2, ownerId)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.toSummary() else null }
            }
        }

    /** The caller's activities, newest first, one page after [after]; `limit + 1` rows tell whether there is more. */
    fun list(
        ownerId: UUID,
        limit: Int,
        after: ActivityCursor?,
    ): ActivityPage {
        val rows =
            db.connection.use { conn ->
                conn
                    .prepareStatement(
                        """
                        SELECT $SUMMARY_COLUMNS FROM activities
                        WHERE owner_id = ? AND (?::timestamptz IS NULL OR (started_at, id) < (?::timestamptz, ?::uuid))
                        ORDER BY started_at DESC, id DESC LIMIT ?
                        """.trimIndent(),
                    ).use { stmt ->
                        val cursorTime = after?.let { Timestamp.from(it.startedAt) }
                        stmt.setObject(1, ownerId)
                        stmt.setTimestamp(2, cursorTime)
                        stmt.setTimestamp(3, cursorTime)
                        stmt.setObject(4, after?.id)
                        stmt.setInt(5, limit + 1)
                        stmt.executeQuery().use { rs ->
                            buildList { while (rs.next()) add(rs.toSummary()) }
                        }
                    }
            }
        val items = rows.take(limit)
        val next =
            if (rows.size > limit) {
                items.last().let { ActivityCursor(Instant.parse(it.startedAt), UUID.fromString(it.id)).toString() }
            } else {
                null
            }
        return ActivityPage(items, next)
    }

    /**
     * The activity's track as a GeoJSON Feature (geometry `null` without a track), or null when the activity does
     * not exist or is not the caller's.
     */
    fun trackGeoJson(
        ownerId: UUID,
        id: UUID,
    ): String? =
        db.connection.use { conn ->
            val sql = "SELECT ST_AsGeoJSON(track) FROM activities WHERE id = ? AND owner_id = ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, id)
                stmt.setObject(2, ownerId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        val geometry = rs.getString(1) ?: "null"
                        """{"type":"Feature","properties":{},"geometry":$geometry}"""
                    } else {
                        null
                    }
                }
            }
        }

    private class Existing(
        val summary: ActivitySummary,
        val sha256: String,
    )

    private fun existing(
        ownerId: UUID,
        clientActivityId: UUID,
    ): Existing? =
        db.connection.use { conn ->
            conn
                .prepareStatement(
                    "SELECT raw_sha256, $SUMMARY_COLUMNS FROM activities WHERE owner_id = ? AND client_activity_id = ?",
                ).use { stmt ->
                    stmt.setObject(1, ownerId)
                    stmt.setObject(2, clientActivityId)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) Existing(rs.toSummary(columnOffset = 1), rs.getString(1)) else null
                    }
                }
        }

    private fun wasDeleted(
        ownerId: UUID,
        clientActivityId: UUID,
    ): Boolean =
        db.connection.use { conn ->
            conn
                .prepareStatement("SELECT 1 FROM deleted_activities WHERE owner_id = ? AND client_activity_id = ?")
                .use { stmt ->
                    stmt.setObject(1, ownerId)
                    stmt.setObject(2, clientActivityId)
                    stmt.executeQuery().use { it.next() }
                }
        }

    private fun resolve(
        existing: Existing,
        sha256: String,
    ): IngestResult = if (existing.sha256 == sha256) IngestResult.Existing(existing.summary) else IngestResult.Conflict

    /** True when the RR sidecar for this activity has been tombstoned (consent revocation, ADR-0021). */
    fun rrDeleted(
        ownerId: UUID,
        clientActivityId: UUID,
    ): Boolean =
        db.connection.use { conn ->
            conn
                .prepareStatement("SELECT 1 FROM deleted_rr WHERE owner_id = ? AND client_activity_id = ?")
                .use { stmt ->
                    stmt.setObject(1, ownerId)
                    stmt.setObject(2, clientActivityId)
                    stmt.executeQuery().use { it.next() }
                }
        }

    /** Whether the activity exists and belongs to the owner: an RR sidecar needs an activity to belong to. */
    fun activityExists(
        ownerId: UUID,
        clientActivityId: UUID,
    ): Boolean =
        db.connection.use { conn ->
            conn.prepareStatement("SELECT 1 FROM activities WHERE owner_id = ? AND client_activity_id = ?").use {
                it.setObject(1, ownerId)
                it.setObject(2, clientActivityId)
                it.executeQuery().use { it.next() }
            }
        }

    /**
     * Remembers the stored heart-rate sidecar of an activity, so a consent revocation can delete exactly that
     * file and an export can include it (ADR-0021). False when the activity is not the caller's.
     */
    fun recordRrSidecar(
        ownerId: UUID,
        clientActivityId: UUID,
        key: String,
        sha256: String,
        sizeBytes: Long,
    ): Boolean =
        db.connection.use { conn ->
            conn
                .prepareStatement(
                    "UPDATE activities SET rr_object_key = ?, rr_sha256 = ?, rr_size_bytes = ? " +
                        "WHERE owner_id = ? AND client_activity_id = ?",
                ).use { stmt ->
                    stmt.setString(1, key)
                    stmt.setString(2, sha256)
                    stmt.setLong(3, sizeBytes)
                    stmt.setObject(4, ownerId)
                    stmt.setObject(5, clientActivityId)
                    stmt.executeUpdate() > 0
                }
        }

    /** The bytes the owner stores: every canonical file, every imported original, every sidecar. */
    fun storedBytesOf(ownerId: UUID): Long =
        db.connection.use { conn ->
            conn
                .prepareStatement(
                    "SELECT coalesce(sum(raw_size_bytes + coalesce(source_size_bytes, 0) " +
                        "+ coalesce(rr_size_bytes, 0)), 0) FROM activities WHERE owner_id = ?",
                ).use {
                    it.setObject(1, ownerId)
                    it.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0 }
                }
        }
}

internal const val SUMMARY_COLUMNS =
    "id, client_activity_id, sport, started_at, final_state, distance_m, elapsed_ms, moving_ms, paused_ms, " +
        "stationary_ms, average_speed_mps, pace_sec_per_km, quality_grade, dropped_records, " +
        "metrics_algorithm_version, quality_algorithm_version, visibility, avg_hr_bpm, max_hr_bpm, hr_source, " +
        "hr_algorithm_version, source"

internal fun ResultSet.toSummary(columnOffset: Int = 0): ActivitySummary {
    var i = columnOffset
    return ActivitySummary(
        id = getObject(++i, UUID::class.java).toString(),
        clientActivityId = getObject(++i, UUID::class.java).toString(),
        sport = getString(++i),
        startedAt = getTimestamp(++i).toInstant().toString(),
        finalState = getString(++i),
        distanceM = getDouble(++i),
        elapsedMs = getLong(++i),
        movingMs = getLong(++i),
        pausedMs = getLong(++i),
        stationaryMs = getLong(++i),
        averageSpeedMps = getDouble(++i),
        paceSecPerKm = getDouble(++i).takeUnless { wasNull() },
        qualityGrade = getString(++i),
        droppedRecords = getInt(++i),
        metricsAlgorithmVersion = getString(++i),
        qualityAlgorithmVersion = getString(++i),
        visibility = getString(++i),
        avgHrBpm = getInt(++i),
        maxHrBpm = getInt(++i),
        hrSource = getString(++i),
        hrAlgorithmVersion = getString(++i),
        source = getString(++i),
    )
}
