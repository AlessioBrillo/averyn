package dev.averyn.android

import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import dev.averyn.android.health.HealthConnectExporter
import dev.averyn.domain.Sport
import dev.averyn.tracking.WorkoutExport
import dev.averyn.tracking.WorkoutHeartRate
import dev.averyn.tracking.WorkoutPoint
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** What is written to Health Connect (TDD-0014): the mapping, without a phone. */
class HealthConnectExporterTest {
    private val start = 1_792_999_200_000L

    private fun point(
        offsetMs: Long,
        latitude: Double = 45.0,
    ) = WorkoutPoint(start + offsetMs, latitude, 9.19, 120.0, 5.0)

    private fun workout(
        sport: Sport = Sport.RUN,
        route: List<WorkoutPoint> = listOf(point(0), point(1_000, 45.0001), point(2_000, 45.0002)),
        heartRate: List<WorkoutHeartRate> = listOf(WorkoutHeartRate(start + 1_000, 140)),
        distanceM: Double = 20.0,
        endOffsetMs: Long = 3_000,
    ) = WorkoutExport("act-1", sport, start, start + endOffsetMs, distanceM, route, heartRate, emptyList())

    private fun records(workout: WorkoutExport) = HealthConnectExporter.toRecords(workout) { ZoneOffset.UTC }

    @Test
    fun aWorkoutIsASessionWithItsRouteTheHeartRateAndTheDistance() {
        val written = records(workout())

        assertEquals(3, written.size)
        val session = written.filterIsInstance<ExerciseSessionRecord>().single()
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, session.exerciseType)
        assertEquals(Instant.ofEpochMilli(start), session.startTime)
        assertEquals(Instant.ofEpochMilli(start + 3_000), session.endTime)
        val route =
            assertNotNull(
                session.exerciseRouteResult.let {
                    it as? androidx.health.connect.client.records.ExerciseRouteResult.Data
                },
            )
        assertEquals(3, route.exerciseRoute.route.size)
        assertEquals(
            1,
            written
                .filterIsInstance<HeartRateRecord>()
                .single()
                .samples.size,
        )
        assertEquals(
            20.0,
            written
                .filterIsInstance<DistanceRecord>()
                .single()
                .distance.inMeters,
            0.001,
        )
    }

    @Test
    fun theSportsMapToTheirExerciseTypes() {
        fun typeOf(sport: Sport) =
            records(workout(sport = sport)).filterIsInstance<ExerciseSessionRecord>().single().exerciseType

        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_BIKING, typeOf(Sport.RIDE))
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_WALKING, typeOf(Sport.WALK))
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_HIKING, typeOf(Sport.HIKE))
    }

    @Test
    fun everyRecordHasAClientIdMadeFromTheActivitySoSavingTwiceDoesNotDuplicate() {
        val ids = records(workout()).map { it.metadata.clientRecordId }

        assertEquals(setOf("averyn-act-1-session", "averyn-act-1-heart-rate", "averyn-act-1-distance"), ids.toSet())
        assertEquals(ids, records(workout()).map { it.metadata.clientRecordId })
    }

    @Test
    fun theRouteHasStrictlyIncreasingTimesAndStaysInsideTheSession() {
        val route =
            listOf(
                point(-5_000), // before the session
                point(0),
                point(0, 45.00005), // the same millisecond: dropped
                point(1_000),
                point(3_000), // at the end: not before it
                point(9_000), // after
            )

        val session = records(workout(route = route)).filterIsInstance<ExerciseSessionRecord>().single()

        val points =
            (session.exerciseRouteResult as androidx.health.connect.client.records.ExerciseRouteResult.Data)
                .exerciseRoute.route
        assertEquals(listOf(start, start + 1_000), points.map { it.time.toEpochMilli() })
    }

    @Test
    fun noHeartRateAndNoDistanceMeansNoSuchRecords() {
        val written = records(workout(heartRate = emptyList(), distanceM = 0.0))

        assertEquals(1, written.size)
        assertTrue(written.single() is ExerciseSessionRecord)
    }

    @Test
    fun aWorkoutThatDoesNotLastIsNotWritten() {
        assertTrue(records(workout(endOffsetMs = 0)).isEmpty())
    }

    @Test
    fun aHeartRateReadingOutsideTheSessionIsLeftOut() {
        val written =
            records(
                workout(heartRate = listOf(WorkoutHeartRate(start - 1_000, 100), WorkoutHeartRate(start + 500, 130))),
            )

        assertEquals(
            listOf(130L),
            written
                .filterIsInstance<HeartRateRecord>()
                .single()
                .samples
                .map { it.beatsPerMinute },
        )
    }
}
