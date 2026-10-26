package dev.averyn.tracking

import dev.averyn.domain.ActivityState
import dev.averyn.domain.Sport

/** One point of the route of a workout. */
class WorkoutPoint(
    val timeMs: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double?,
    /** The horizontal accuracy the platform reported, or null when it did not. */
    val accuracyM: Double?,
)

/** One heart-rate reading of a workout. */
class WorkoutHeartRate(
    val timeMs: Long,
    val bpm: Int,
)

/** A stretch of the workout during which the user had paused. */
class WorkoutPause(
    val startMs: Long,
    val endMs: Long,
)

/**
 * A finished activity, in the shape the health platforms of the phones take (HealthKit, Health Connect): what to
 * write, once, for both (TDD-0014). Each platform only maps it to its own API.
 *
 * What it holds is the activity as Averyn counts it: the route is the accepted samples of the recording time, the
 * heart rate is the readings of the recording time, and the distance is the one the metrics computed.
 */
class WorkoutExport(
    val activityId: String,
    val sport: Sport,
    val startMs: Long,
    val endMs: Long,
    val distanceM: Double,
    val route: List<WorkoutPoint>,
    val heartRate: List<WorkoutHeartRate>,
    val pauses: List<WorkoutPause>,
)

/**
 * The workout of a stored activity, or null when it is not a finished activity with a route (a platform workout
 * needs a start, an end and something that happened). An extension function so that Swift reaches it as
 * `store.workoutExport(activityId:)`.
 *
 * Nothing is invented: a sample the quality rules exclude from the distance is not in the route (the same rule
 * as `ACCEPTED_ONLY` in the GPX export), a reading taken while the user had paused is not a heart rate of the
 * workout, and the pauses are the real ones.
 */
fun ActivityStore.workoutExport(activityId: String): WorkoutExport? {
    val metadata = metadataOf(activityId) ?: return null
    val assessor = QualityAssessor()
    val route = mutableListOf<WorkoutPoint>()
    val heartRate = mutableListOf<WorkoutHeartRate>()
    val pauses = mutableListOf<WorkoutPause>()
    var state: ActivityState? = null
    var pausedAtMs: Long? = null
    var endMs: Long? = null
    var completed = false
    forEachRecord(activityId) { record ->
        when (record) {
            is ActivityRecord.Event -> {
                val event = record.event
                state = event.state
                if (event.state == ActivityState.PAUSED) pausedAtMs = event.timeMs
                if (event.state == ActivityState.RECORDING) {
                    pausedAtMs?.let { pauses += WorkoutPause(it, event.timeMs) }
                    pausedAtMs = null
                }
                if (event.state == ActivityState.STOPPING || event.state == ActivityState.COMPLETED) {
                    endMs = event.timeMs
                    pausedAtMs?.let { pauses += WorkoutPause(it, event.timeMs) }
                    pausedAtMs = null
                }
                if (event.state == ActivityState.COMPLETED) completed = true
            }
            is ActivityRecord.Sample -> {
                val sample = record.sample
                if (state == ActivityState.RECORDING && assessor.assess(sample).none { it.excludesFromDistance }) {
                    route +=
                        WorkoutPoint(
                            sample.timeMs,
                            sample.latitude,
                            sample.longitude,
                            sample.altitudeM?.takeIf { it.isFinite() },
                            sample.horizontalAccuracyM,
                        )
                }
            }
            is ActivityRecord.HeartRate -> {
                val sample = record.sample
                if (state == ActivityState.RECORDING && sample.bpm in 1..240) {
                    heartRate += WorkoutHeartRate(sample.timeMs, sample.bpm)
                }
            }
        }
    }
    val end = endMs
    if (!completed || end == null || route.isEmpty()) return null
    val distance = replay(this, activityId).metrics.snapshot().distanceM
    return WorkoutExport(activityId, metadata.sport, metadata.startedAtMs, end, distance, route, heartRate, pauses)
}
