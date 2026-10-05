package dev.averyn.backend.activities

import dev.averyn.backend.storage.RawObjectStore
import dev.averyn.domain.ActivityState
import dev.averyn.domain.LocationSample
import dev.averyn.metrics.DISTANCE_ALGORITHM_VERSION
import dev.averyn.metrics.SPEED_ALGORITHM_VERSION
import dev.averyn.metrics.TIME_ALGORITHM_VERSION
import dev.averyn.tracking.ActivityMetadata
import dev.averyn.tracking.ActivityStore
import dev.averyn.tracking.ReplayResult
import dev.averyn.tracking.replay
import kotlinx.io.files.Path
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.io.path.name

/** Metric algorithms an activity row was computed with (a change of any of them is a new version, never an edit). */
const val METRICS_ALGORITHM_VERSION = "$DISTANCE_ALGORITHM_VERSION,$TIME_ALGORITHM_VERSION,$SPEED_ALGORITHM_VERSION"

@Serializable
data class ActivitySummary(
    val id: String,
    val clientActivityId: String,
    val sport: String,
    val startedAt: String,
    val finalState: String,
    val distanceM: Double,
    val elapsedMs: Long,
    val movingMs: Long,
    val pausedMs: Long,
    val averageSpeedMps: Double,
    /** null when the average speed is 0 (speed-v1). */
    val paceSecPerKm: Double?,
    val qualityGrade: String,
    val droppedRecords: Int,
    val metricsAlgorithmVersion: String,
    val qualityAlgorithmVersion: String,
    val visibility: String,
)

@Serializable
data class ActivityPage(
    val items: List<ActivitySummary>,
    /** Opaque; null on the last page. */
    val nextCursor: String?,
)

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
}

/**
 * Turns an uploaded raw activity file into an `activities` row (TDD-0002 §5.2): replay it with the shared device
 * code, store the file byte-for-byte, record the derived summary. Blocking (JDBC, S3): call from `Dispatchers.IO`.
 */
class ActivityIngest(
    private val db: DataSource,
    private val raw: RawObjectStore,
) {
    /**
     * @param file the uploaded body, already on disk and named `<clientActivityId>.jsonl` (the store layout)
     * @param sha256 lower-case hex SHA-256 of that file
     */
    fun ingest(
        ownerId: UUID,
        clientActivityId: UUID,
        file: java.nio.file.Path,
        sha256: String,
    ): IngestResult {
        existing(ownerId, clientActivityId)?.let { return resolve(it, sha256) }

        val store = ActivityStore(Path(file.parent.toString()))
        val activityId = file.name.removeSuffix(".jsonl")
        val track = ArrayList<String>() // "lon lat" of every sample that counted towards the distance
        val metadata: ActivityMetadata
        val replayed: ReplayResult
        try {
            metadata = store.metadataOf(activityId) ?: return IngestResult.Invalid("no activity metadata line")
            if (!metadata.activityId.equals(clientActivityId.toString(), ignoreCase = true)) {
                return IngestResult.Invalid("activity id does not match the file's metadata")
            }
            replayed =
                replay(
                    store,
                    activityId,
                ) { sample: LocationSample -> track += "${sample.longitude} ${sample.latitude}" }
        } catch (e: RuntimeException) {
            // A line that parses but violates the model (e.g. latitude out of range) is an invalid upload.
            return IngestResult.Invalid("unreadable activity file")
        }
        if (replayed.lastState != ActivityState.COMPLETED && replayed.lastState != ActivityState.FAILED) {
            return IngestResult.Invalid("activity is not finished (state ${replayed.lastState})")
        }

        // Object first, row second: a failed insert leaves an orphan object, never a row without its raw file.
        // The hash in the key means two different uploads of one id can never overwrite each other's bytes.
        val key = "raw/$ownerId/$clientActivityId/$sha256.jsonl"
        raw.put(key, file)

        val snapshot = replayed.metrics.snapshot()
        val quality = replayed.quality.build()
        val wkt = if (track.size >= 2) "LINESTRING(${track.joinToString(",")})" else null
        val inserted =
            db.connection.use { conn ->
                conn
                    .prepareStatement(
                        """
                        INSERT INTO activities (owner_id, client_activity_id, sport, started_at, final_state,
                            raw_object_key, raw_sha256, raw_size_bytes, distance_m, elapsed_ms, moving_ms, paused_ms,
                            average_speed_mps, pace_sec_per_km, quality_grade, dropped_records,
                            metrics_algorithm_version, quality_algorithm_version, track)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ST_GeomFromText(?, 4326))
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
                        stmt.setDouble(++i, snapshot.averageSpeedMps)
                        stmt.setObject(++i, snapshot.paceSecPerKm, java.sql.Types.DOUBLE)
                        stmt.setString(++i, quality.grade.name)
                        stmt.setInt(++i, replayed.recordLosses.values.sum())
                        stmt.setString(++i, METRICS_ALGORITHM_VERSION)
                        stmt.setString(++i, quality.algorithmVersion)
                        stmt.setString(++i, wkt)
                        stmt.executeQuery().use { rs -> if (rs.next()) rs.toSummary() else null }
                    }
            }
        // No row back: a concurrent upload of this id won the race between our check and our insert.
        return inserted?.let { IngestResult.Created(it) }
            ?: resolve(checkNotNull(existing(ownerId, clientActivityId)), sha256)
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

    private fun resolve(
        existing: Existing,
        sha256: String,
    ): IngestResult = if (existing.sha256 == sha256) IngestResult.Existing(existing.summary) else IngestResult.Conflict
}

private const val SUMMARY_COLUMNS =
    "id, client_activity_id, sport, started_at, final_state, distance_m, elapsed_ms, moving_ms, paused_ms, " +
        "average_speed_mps, pace_sec_per_km, quality_grade, dropped_records, metrics_algorithm_version, " +
        "quality_algorithm_version, visibility"

private fun ResultSet.toSummary(columnOffset: Int = 0): ActivitySummary {
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
        averageSpeedMps = getDouble(++i),
        paceSecPerKm = getDouble(++i).takeUnless { wasNull() },
        qualityGrade = getString(++i),
        droppedRecords = getInt(++i),
        metricsAlgorithmVersion = getString(++i),
        qualityAlgorithmVersion = getString(++i),
        visibility = getString(++i),
    )
}
