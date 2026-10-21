package dev.averyn.backend.activities

import dev.averyn.analysis.ANALYSIS_SCHEMA_VERSION
import dev.averyn.analysis.BEST_EFFORTS_ALGORITHM_VERSION
import dev.averyn.analysis.bestEffortDistancesM
import dev.averyn.domain.Sport
import kotlinx.serialization.Serializable
import java.util.UUID
import javax.sql.DataSource

/** One personal record: the fastest window of a distance, and the activity that holds it. */
@Serializable
data class PersonalRecord(
    /** The activity the record was made in. */
    val activityId: String,
    /** When it was made: the start of the activity. */
    val achievedAt: String,
    /** The moving time of the effort. */
    val durationMs: Long,
    /** The wall time of the effort, the stops inside it included. */
    val elapsedMs: Long,
    val avgSpeedMps: Double,
    val paceSecPerKm: Double?,
    /** Where the activity comes from: `device` (recorded) or `gpx` (imported). */
    val source: String,
    /** The quality of the activity that holds the record. */
    val qualityGrade: String,
)

/** One target distance of a sport, with the record of it when there is one. */
@Serializable
data class RecordTarget(
    val distanceM: Double,
    /** Null when the account has no record of this distance yet. */
    val record: PersonalRecord?,
)

/** The records of one sport: every target distance, with the record of it or none. */
@Serializable
data class SportRecords(
    val sport: String,
    val targets: List<RecordTarget>,
)

/** The personal records of an account. */
@Serializable
data class PersonalRecords(
    /** The version of the algorithm that produced the numbers. */
    val algorithmVersion: String,
    /**
     * The activities of the account that have no analysis of the current version: their best efforts are
     * missing, and the records of the account are incomplete while this is not zero. The page says so.
     */
    val pendingActivities: Int,
    val sports: List<SportRecords>,
)

/**
 * The personal records of an account (TDD-0010, ADR-0024).
 *
 * A record is not stored: it is the fastest best effort of the account for a sport and a distance. The query is
 * the projection, so deleting the activity that held a record promotes the next one, with nothing to keep in
 * step.
 *
 * The eligibility of an activity is a rule of the product, not a measurement: it is the same rule for every
 * record, and it is written down in one place.
 */
class PersonalRecordStore(
    private val db: DataSource,
) {
    /** The records of the owner: the fastest effort of every sport and target distance. */
    fun records(ownerId: UUID): PersonalRecords {
        val fastest = fastestEfforts(ownerId)
        val sports =
            Sport.values().mapNotNull { sport ->
                val held = fastest.entries.filter { it.key.first == sport.name }.associate { it.key.second to it.value }
                if (held.isEmpty()) {
                    null
                } else {
                    SportRecords(
                        sport = sport.name,
                        targets =
                            bestEffortDistancesM(sport).map { distance ->
                                RecordTarget(distanceM = distance, record = held[distance])
                            },
                    )
                }
            }
        return PersonalRecords(
            algorithmVersion = BEST_EFFORTS_ALGORITHM_VERSION,
            pendingActivities = pendingActivities(ownerId),
            sports = sports,
        )
    }

    /**
     * The activities of the owner that have no analysis of the current version: their best efforts are missing,
     * and the records of the account are incomplete until they are computed.
     */
    fun pendingActivities(ownerId: UUID): Int =
        db.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    SELECT count(*)
                    FROM activities a LEFT JOIN activity_analyses an ON an.activity_id = a.id
                    WHERE a.owner_id = ?::uuid
                        AND (an.activity_id IS NULL
                            OR an.best_efforts_version <> ?::text
                            OR an.analysis_schema_version <> ?::integer)
                    """.trimIndent(),
                ).use { stmt ->
                    stmt.setObject(1, ownerId)
                    stmt.setString(2, BEST_EFFORTS_ALGORITHM_VERSION)
                    stmt.setInt(3, ANALYSIS_SCHEMA_VERSION)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) rs.getInt(1) else 0
                    }
                }
        }

    /**
     * The fastest best effort of the owner for every sport and distance.
     *
     * An activity is eligible when it finished, when it comes from Averyn or from a GPX file, and when its
     * quality is not `POOR`: the platform itself says the distance of such an activity is not reliable. An
     * imported file is eligible even when its accuracy was never measured: `UNKNOWN` is not a bad grade, it is
     * an unmeasured one, and the history of the user is worth more than a strict filter.
     */
    private fun fastestEfforts(ownerId: UUID): Map<Pair<String, Double>, PersonalRecord> =
        db.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    SELECT DISTINCT ON (a.sport, e.distance_m)
                        a.sport, e.distance_m, e.duration_ms, e.elapsed_ms, e.avg_speed_mps, e.pace_sec_per_km,
                        a.id, a.started_at, a.source, a.quality_grade
                    FROM activity_best_efforts e
                    JOIN activities a ON a.id = e.activity_id
                    JOIN activity_analyses an ON an.activity_id = a.id
                    WHERE a.owner_id = ?::uuid
                        AND a.final_state = 'COMPLETED'
                        AND a.source IN ('device', 'gpx')
                        AND a.quality_grade IN ('GOOD', 'FAIR', 'UNKNOWN')
                        AND an.best_efforts_version = ?::text
                    ORDER BY a.sport, e.distance_m, e.duration_ms, a.started_at, a.id
                    """.trimIndent(),
                ).use { stmt ->
                    stmt.setObject(1, ownerId)
                    stmt.setString(2, BEST_EFFORTS_ALGORITHM_VERSION)
                    stmt.executeQuery().use { rs ->
                        buildMap {
                            while (rs.next()) {
                                val sport = rs.getString(1)
                                val distanceM = rs.getDouble(2)
                                put(
                                    sport to distanceM,
                                    PersonalRecord(
                                        activityId = rs.getObject(7, UUID::class.java).toString(),
                                        achievedAt = rs.getTimestamp(8).toInstant().toString(),
                                        durationMs = rs.getLong(3),
                                        elapsedMs = rs.getLong(4),
                                        avgSpeedMps = rs.getDouble(5),
                                        paceSecPerKm = rs.getDoubleOrNull(6),
                                        source = rs.getString(9),
                                        qualityGrade = rs.getString(10),
                                    ),
                                )
                            }
                        }
                    }
                }
        }

    private fun java.sql.ResultSet.getDoubleOrNull(index: Int): Double? =
        getDouble(index).let { if (wasNull()) null else it }
}
