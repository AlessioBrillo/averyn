package dev.averyn.android.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Length
import dev.averyn.domain.Sport
import dev.averyn.tracking.WorkoutExport
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Whether this phone can take a workout (TDD-0014). */
internal enum class HealthConnectAvailability { AVAILABLE, UPDATE_NEEDED, UNAVAILABLE }

/**
 * Writes a finished activity to Health Connect, at the user's request (TDD-0014): the workout with its route, the
 * heart rate and the distance. Averyn only writes; it reads nothing from Health Connect.
 *
 * Saving the same activity twice does not duplicate it: every record has a client id made from the id of the
 * activity, and Health Connect keeps one record per client id and version.
 */
internal object HealthConnectExporter {
    /** What the user is asked to allow: exactly the four things that are written, nothing that is read. */
    val PERMISSIONS: Set<String> =
        setOf(
            HealthPermission.getWritePermission(ExerciseSessionRecord::class),
            HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE,
            HealthPermission.getWritePermission(HeartRateRecord::class),
            HealthPermission.getWritePermission(DistanceRecord::class),
        )

    fun availability(context: Context): HealthConnectAvailability =
        when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthConnectAvailability.UPDATE_NEEDED
            else -> HealthConnectAvailability.UNAVAILABLE
        }

    /** Writes the workout; returns how many records Health Connect took. Needs the [PERMISSIONS]. */
    suspend fun write(
        context: Context,
        workout: WorkoutExport,
    ): Int {
        val records = toRecords(workout)
        if (records.isEmpty()) return 0
        return HealthConnectClient
            .getOrCreate(context)
            .insertRecords(records)
            .recordIdsList.size
    }

    /**
     * The records of a workout: the session (with its route), the heart rate and the distance. Pure, so that what is
     * written can be tested without a phone. The pauses are not written: a Health Connect session has no pause.
     */
    fun toRecords(
        workout: WorkoutExport,
        offsetAt: (Instant) -> ZoneOffset = { ZoneId.systemDefault().rules.getOffset(it) },
    ): List<Record> {
        val start = Instant.ofEpochMilli(workout.startMs)
        val end = Instant.ofEpochMilli(workout.endMs)
        // A session must last: a start that is not before the end cannot be written.
        if (!start.isBefore(end)) return emptyList()
        val startOffset = offsetAt(start)
        val endOffset = offsetAt(end)
        val device = Device(type = Device.TYPE_PHONE)

        fun metadata(kind: String) =
            Metadata.activelyRecorded(device = device, clientRecordId = "averyn-${workout.activityId}-$kind")

        val route = routeOf(workout, start, end)
        val records = mutableListOf<Record>()
        records +=
            ExerciseSessionRecord(
                startTime = start,
                startZoneOffset = startOffset,
                endTime = end,
                endZoneOffset = endOffset,
                metadata = metadata("session"),
                exerciseType = exerciseTypeOf(workout.sport),
                title = titleOf(workout.sport),
                exerciseRoute = route,
            )

        val readings =
            workout.heartRate
                .filter { it.timeMs in workout.startMs..workout.endMs }
                .map { HeartRateRecord.Sample(Instant.ofEpochMilli(it.timeMs), it.bpm.toLong()) }
        if (readings.isNotEmpty()) {
            records +=
                HeartRateRecord(
                    startTime = start,
                    startZoneOffset = startOffset,
                    endTime = end,
                    endZoneOffset = endOffset,
                    samples = readings,
                    metadata = metadata("heart-rate"),
                )
        }
        if (workout.distanceM > 0.0) {
            records +=
                DistanceRecord(
                    startTime = start,
                    startZoneOffset = startOffset,
                    endTime = end,
                    endZoneOffset = endOffset,
                    distance = Length.meters(workout.distanceM),
                    metadata = metadata("distance"),
                )
        }
        return records
    }

    /**
     * The route Health Connect accepts: its points have strictly increasing times and lie inside the session. A
     * second fix in the same millisecond is dropped, not moved.
     */
    private fun routeOf(
        workout: WorkoutExport,
        start: Instant,
        end: Instant,
    ): ExerciseRoute? {
        val points = mutableListOf<ExerciseRoute.Location>()
        var lastTime: Instant? = null
        for (point in workout.route) {
            val time = Instant.ofEpochMilli(point.timeMs)
            if (time.isBefore(start) || !time.isBefore(end)) continue
            if (lastTime != null && !time.isAfter(lastTime)) continue
            lastTime = time
            points +=
                ExerciseRoute.Location(
                    time = time,
                    latitude = point.latitude,
                    longitude = point.longitude,
                    horizontalAccuracy = point.accuracyM?.takeIf { it >= 0.0 }?.let { Length.meters(it) },
                    altitude = point.altitudeM?.let { Length.meters(it) },
                )
        }
        return if (points.isEmpty()) null else ExerciseRoute(points)
    }

    private fun exerciseTypeOf(sport: Sport): Int =
        when (sport) {
            Sport.RUN -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
            Sport.RIDE -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING
            Sport.WALK -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING
            Sport.HIKE -> ExerciseSessionRecord.EXERCISE_TYPE_HIKING
        }

    private fun titleOf(sport: Sport): String =
        when (sport) {
            Sport.RUN -> "Run"
            Sport.RIDE -> "Ride"
            Sport.WALK -> "Walk"
            Sport.HIKE -> "Hike"
        }
}
