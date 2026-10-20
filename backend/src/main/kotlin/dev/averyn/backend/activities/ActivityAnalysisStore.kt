package dev.averyn.backend.activities

import dev.averyn.analysis.ANALYSIS_SCHEMA_VERSION
import dev.averyn.analysis.ActivityAnalysis
import dev.averyn.analysis.AnalysisAlgorithmVersions
import dev.averyn.analysis.AnalysisElevation
import dev.averyn.analysis.AnalysisHeartRate
import dev.averyn.analysis.AnalysisSeries
import dev.averyn.analysis.AnalysisSplit
import dev.averyn.analysis.AnalysisTotals
import dev.averyn.analysis.ELEVATION_ALGORITHM_VERSION
import dev.averyn.analysis.SERIES_ALGORITHM_VERSION
import dev.averyn.analysis.SPLITS_ALGORITHM_VERSION
import dev.averyn.sensors.HR_ALGORITHM_VERSION
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.util.UUID
import javax.sql.DataSource

/**
 * The analysis of the activities, in the database (TDD-0009, ADR-0023).
 *
 * The analysis is derived data: the raw file is the system of record (ADR-0016) and every number here can be
 * recomputed. What is stored is the *current* version of it: a row whose versions are not the current ones is
 * outdated and is recomputed on the next read.
 */
class ActivityAnalysisStore(
    private val db: DataSource,
) {
    private val json = Json

    /** The analysis of the activity, or null when it has none (or it is not the caller's). */
    fun find(
        ownerId: UUID,
        activityId: UUID,
    ): ActivityAnalysis? =
        db.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    SELECT $ANALYSIS_COLUMNS
                    FROM activities a JOIN activity_analyses an ON an.activity_id = a.id
                    WHERE a.id = ? AND a.owner_id = ?
                    """.trimIndent(),
                ).use { stmt ->
                    stmt.setObject(1, activityId)
                    stmt.setObject(2, ownerId)
                    stmt.executeQuery().use { rs ->
                        if (!rs.next()) return null
                        val analysis = rs.toAnalysis()
                        analysis.copy(splits = readSplits(conn, activityId))
                    }
                }
        }

    /** Whether the stored analysis was computed with the algorithms of this build. */
    fun isCurrent(activityId: UUID): Boolean =
        db.connection.use { conn ->
            conn
                .prepareStatement(
                    "SELECT splits_version, elevation_version, series_version, series_schema_version, " +
                        "hr_algorithm_version FROM activity_analyses WHERE activity_id = ?",
                ).use { stmt ->
                    stmt.setObject(1, activityId)
                    stmt.executeQuery().use { rs ->
                        if (!rs.next()) return false
                        rs.getString(1) == SPLITS_ALGORITHM_VERSION &&
                            rs.getString(2) == ELEVATION_ALGORITHM_VERSION &&
                            rs.getString(3) == SERIES_ALGORITHM_VERSION &&
                            rs.getInt(4) == ANALYSIS_SCHEMA_VERSION &&
                            rs.getString(5) == HR_ALGORITHM_VERSION
                    }
                }
        }

    /**
     * Stores the analysis of the activity: the summary and the series in one row, the splits in one row each.
     * Replacing the analysis of an activity is one transaction: a reader never sees half of it.
     */
    fun save(
        activityId: UUID,
        analysis: ActivityAnalysis,
    ) {
        db.connection.use { conn ->
            conn.autoCommit = false
            try {
                conn
                    .prepareStatement(
                        """
                        INSERT INTO activity_analyses (activity_id, splits_version, elevation_version,
                            series_version, series_schema_version, split_length_m, elevation_source,
                            elevation_gain_m, elevation_loss_m, min_altitude_m, max_altitude_m, avg_altitude_m,
                            elevation_coverage_percent, hr_algorithm_version, hr_min_bpm, hr_coverage_percent,
                            hr_dropouts, hr_artifacts, series)
                        VALUES (?::uuid, ?::text, ?::text, ?::text, ?::integer, ?::double precision, ?::text,
                            ?::double precision, ?::double precision, ?::double precision, ?::double precision,
                            ?::double precision, ?::double precision, ?::text, ?::integer, ?::double precision,
                            ?::integer, ?::integer, ?::jsonb)
                        ON CONFLICT (activity_id) DO UPDATE SET
                            splits_version = EXCLUDED.splits_version,
                            elevation_version = EXCLUDED.elevation_version,
                            series_version = EXCLUDED.series_version,
                            series_schema_version = EXCLUDED.series_schema_version,
                            split_length_m = EXCLUDED.split_length_m,
                            elevation_source = EXCLUDED.elevation_source,
                            elevation_gain_m = EXCLUDED.elevation_gain_m,
                            elevation_loss_m = EXCLUDED.elevation_loss_m,
                            min_altitude_m = EXCLUDED.min_altitude_m,
                            max_altitude_m = EXCLUDED.max_altitude_m,
                            avg_altitude_m = EXCLUDED.avg_altitude_m,
                            elevation_coverage_percent = EXCLUDED.elevation_coverage_percent,
                            hr_algorithm_version = EXCLUDED.hr_algorithm_version,
                            hr_min_bpm = EXCLUDED.hr_min_bpm,
                            hr_coverage_percent = EXCLUDED.hr_coverage_percent,
                            hr_dropouts = EXCLUDED.hr_dropouts,
                            hr_artifacts = EXCLUDED.hr_artifacts,
                            series = EXCLUDED.series,
                            computed_at = now()
                        """.trimIndent(),
                    ).use { stmt ->
                        var i = 0
                        stmt.setObject(++i, activityId)
                        stmt.setString(++i, analysis.algorithmVersions.splits)
                        stmt.setString(++i, analysis.algorithmVersions.elevation)
                        stmt.setString(++i, analysis.algorithmVersions.series)
                        stmt.setInt(++i, analysis.schemaVersion)
                        stmt.setDouble(++i, analysis.splitLengthM)
                        val elevation = analysis.elevation
                        stmt.setString(++i, elevation?.source)
                        stmt.setDoubleOrNull(++i, elevation?.gainM)
                        stmt.setDoubleOrNull(++i, elevation?.lossM)
                        stmt.setDoubleOrNull(++i, elevation?.minM)
                        stmt.setDoubleOrNull(++i, elevation?.maxM)
                        stmt.setDoubleOrNull(++i, elevation?.avgM)
                        stmt.setDoubleOrNull(++i, elevation?.coveragePercent)
                        val heart = analysis.heartRate
                        stmt.setString(++i, analysis.algorithmVersions.heartRate)
                        stmt.setIntOrNull(++i, heart?.minBpm)
                        stmt.setDoubleOrNull(++i, heart?.coveragePercent)
                        stmt.setIntOrNull(++i, heart?.dropouts)
                        stmt.setIntOrNull(++i, heart?.artifacts)
                        stmt.setObject(
                            ++i,
                            json.encodeToString(AnalysisSeries.serializer(), analysis.series),
                            Types.OTHER,
                        )
                        stmt.executeUpdate()
                    }
                conn.prepareStatement("DELETE FROM activity_splits WHERE activity_id = ?").use { stmt ->
                    stmt.setObject(1, activityId)
                    stmt.executeUpdate()
                }
                conn
                    .prepareStatement(
                        """
                        INSERT INTO activity_splits (activity_id, idx, start_distance_m, end_distance_m, elapsed_ms,
                            moving_ms, avg_speed_mps, elevation_gain_m, elevation_loss_m, avg_hr_bpm, partial)
                        VALUES (?::uuid, ?::integer, ?::double precision, ?::double precision, ?::bigint, ?::bigint,
                            ?::double precision, ?::double precision, ?::double precision, ?::integer, ?::boolean)
                        """.trimIndent(),
                    ).use { stmt ->
                        analysis.splits.forEach { split ->
                            var i = 0
                            stmt.setObject(++i, activityId)
                            stmt.setInt(++i, split.index)
                            stmt.setDouble(++i, split.startDistanceM)
                            stmt.setDouble(++i, split.endDistanceM)
                            stmt.setLong(++i, split.elapsedMs)
                            stmt.setLong(++i, split.movingMs)
                            stmt.setDouble(++i, split.avgSpeedMps)
                            stmt.setDoubleOrNull(++i, split.elevationGainM)
                            stmt.setDoubleOrNull(++i, split.elevationLossM)
                            stmt.setIntOrNull(++i, split.avgHrBpm)
                            stmt.setBoolean(++i, split.partial)
                            stmt.addBatch()
                        }
                        stmt.executeBatch()
                    }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
    }

    /**
     * The activities whose analysis is missing or was computed with another version of the algorithms: what the
     * backfill has to do. The CLI runs it, never a request (there is no worker in the backend).
     */
    fun outdated(): List<OutdatedActivity> =
        db.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    SELECT a.id, a.client_activity_id, a.raw_object_key
                    FROM activities a LEFT JOIN activity_analyses an ON an.activity_id = a.id
                    WHERE an.activity_id IS NULL
                        OR an.splits_version <> ?::text
                        OR an.elevation_version <> ?::text
                        OR an.series_version <> ?::text
                        OR an.series_schema_version <> ?::integer
                        OR an.hr_algorithm_version <> ?::text
                    ORDER BY a.started_at, a.id
                    """.trimIndent(),
                ).use { stmt ->
                    var i = 0
                    stmt.setString(++i, SPLITS_ALGORITHM_VERSION)
                    stmt.setString(++i, ELEVATION_ALGORITHM_VERSION)
                    stmt.setString(++i, SERIES_ALGORITHM_VERSION)
                    stmt.setInt(++i, ANALYSIS_SCHEMA_VERSION)
                    stmt.setString(++i, HR_ALGORITHM_VERSION)
                    stmt.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    OutdatedActivity(
                                        id = rs.getObject(1, UUID::class.java),
                                        clientActivityId = rs.getObject(2, UUID::class.java),
                                        rawObjectKey = rs.getString(3),
                                    ),
                                )
                            }
                        }
                    }
                }
        }

    private fun readSplits(
        conn: Connection,
        activityId: UUID,
    ): List<AnalysisSplit> =
        conn
            .prepareStatement(
                """
                SELECT idx, start_distance_m, end_distance_m, elapsed_ms, moving_ms, avg_speed_mps,
                    elevation_gain_m, elevation_loss_m, avg_hr_bpm, partial
                FROM activity_splits WHERE activity_id = ? ORDER BY idx
                """.trimIndent(),
            ).use { stmt ->
                stmt.setObject(1, activityId)
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                AnalysisSplit(
                                    index = rs.getInt(1),
                                    startDistanceM = rs.getDouble(2),
                                    endDistanceM = rs.getDouble(3),
                                    elapsedMs = rs.getLong(4),
                                    movingMs = rs.getLong(5),
                                    avgSpeedMps = rs.getDouble(6),
                                    elevationGainM = rs.getDoubleOrNull(7),
                                    elevationLossM = rs.getDoubleOrNull(8),
                                    avgHrBpm = rs.getIntOrNull(9),
                                    partial = rs.getBoolean(10),
                                ),
                            )
                        }
                    }
                }
            }

    private fun ResultSet.toAnalysis(): ActivityAnalysis {
        val elevation =
            getString(6)?.let {
                AnalysisElevation(
                    source = it,
                    gainM = getDouble(7),
                    lossM = getDouble(8),
                    minM = getDouble(9),
                    maxM = getDouble(10),
                    avgM = getDouble(11),
                    coveragePercent = getDouble(12),
                )
            }
        val heartRate =
            getString(13)?.let {
                AnalysisHeartRate(
                    minBpm = getInt(14),
                    avgBpm = getInt(24),
                    maxBpm = getInt(25),
                    coveragePercent = getDouble(15),
                    dropouts = getInt(16),
                    artifacts = getInt(17),
                )
            }
        return ActivityAnalysis(
            schemaVersion = getInt(4),
            algorithmVersions =
                AnalysisAlgorithmVersions(
                    splits = getString(1),
                    elevation = getString(2),
                    series = getString(3),
                    heartRate = getString(13),
                ),
            splitLengthM = getDouble(5),
            totals =
                AnalysisTotals(
                    distanceM = getDouble(19),
                    elapsedMs = getLong(20),
                    movingMs = getLong(21),
                    pausedMs = getLong(22),
                    stationaryMs = getLong(23),
                ),
            elevation = elevation,
            heartRate = heartRate,
            splits = emptyList(),
            series = json.decodeFromString(AnalysisSeries.serializer(), getString(18)),
        )
    }

    private fun PreparedStatement.setDoubleOrNull(
        index: Int,
        value: Double?,
    ) {
        if (value == null) setNull(index, Types.DOUBLE) else setDouble(index, value)
    }

    private fun PreparedStatement.setIntOrNull(
        index: Int,
        value: Int?,
    ) {
        if (value == null) setNull(index, Types.INTEGER) else setInt(index, value)
    }

    private fun ResultSet.getDoubleOrNull(index: Int): Double? = getDouble(index).let { if (wasNull()) null else it }

    private fun ResultSet.getIntOrNull(index: Int): Int? = getInt(index).let { if (wasNull()) null else it }

    private companion object {
        /**
         * The analysis and the totals of the activity, in the order [toAnalysis] reads them:
         * 1-3 the algorithm versions, 4 the schema version, 5 the split length, 6-12 the elevation,
         * 13-17 the heart rate, 18 the series, 19-23 the totals, 24-25 the heart rate of the summary.
         */
        const val ANALYSIS_COLUMNS =
            "an.splits_version, an.elevation_version, an.series_version, an.series_schema_version, " +
                "an.split_length_m, an.elevation_source, an.elevation_gain_m, an.elevation_loss_m, " +
                "an.min_altitude_m, an.max_altitude_m, an.avg_altitude_m, an.elevation_coverage_percent, " +
                "an.hr_algorithm_version, an.hr_min_bpm, an.hr_coverage_percent, an.hr_dropouts, " +
                "an.hr_artifacts, an.series, a.distance_m, a.elapsed_ms, a.moving_ms, a.paused_ms, " +
                "a.stationary_ms, a.avg_hr_bpm, a.max_hr_bpm"
    }
}

/** An activity whose analysis is missing or outdated: what the backfill has to compute. */
data class OutdatedActivity(
    val id: UUID,
    val clientActivityId: UUID,
    val rawObjectKey: String,
)
