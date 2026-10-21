package dev.averyn.analysis

import dev.averyn.domain.LocationSample
import dev.averyn.metrics.STATIONARY_SPEED_MPS
import dev.averyn.metrics.haversineMeters

/**
 * One point of the track: a sample, the distance from the start and the time from the first sample.
 *
 * The distance is the one the metrics and the splits use (`distance-v1`): the best efforts must not be built
 * on a different track than the rest of the analysis, or the same activity would have two distances.
 */
internal class Point(
    val sample: LocationSample,
    /** The recording interval the point belongs to: a change of number is a pause. */
    val segment: Int,
    val distanceM: Double,
    val elapsedMs: Long,
    /** The altitude of the sample, null when it has none (or when it is not a number). */
    val altitudeM: Double?,
)

/**
 * One stretch between two accepted samples: the unit the distance and the time are counted in.
 *
 * [movingMs] is the whole duration of the leg or zero: a leg moves or it does not, and the interpolation of a
 * boundary takes the same share of the moving time as of the distance.
 */
internal class Leg(
    /** The index of the point the leg starts at, in the track. */
    val startIndex: Int,
    /** The index of the point the leg ends at, in the track. */
    val endIndex: Int,
    val startDistanceM: Double,
    val endDistanceM: Double,
    val startElapsedMs: Long,
    val endElapsedMs: Long,
    /** The wall time of the leg. */
    val durationMs: Long,
    /** The moving time of the leg: its duration when it moves, zero when it does not. */
    val movingMs: Long,
) {
    val distanceM: Double get() = endDistanceM - startDistanceM
    val moving: Boolean get() = movingMs > 0
}

/** The track of an activity: its points and the legs between them. */
internal class Track(
    val points: List<Point>,
    val legs: List<Leg>,
)

/**
 * The track of the accepted samples of every continuous recording interval.
 *
 * The distance carries over from one interval to the next: a pause is a break in the time, not in the distance
 * (the same rule as `distance-v1` and the splits).
 */
internal fun buildTrack(segments: List<List<LocationSample>>): Track {
    val origin = segments.firstOrNull()?.firstOrNull()?.elapsedRealtimeMs ?: 0L
    val points = mutableListOf<Point>()
    val legs = mutableListOf<Leg>()
    var cumulative = 0.0
    segments.forEachIndexed { index, segment ->
        var previous: Point? = null
        for (sample in segment) {
            val distance =
                if (previous == null) {
                    cumulative
                } else {
                    cumulative +
                        haversineMeters(
                            previous.sample.latitude,
                            previous.sample.longitude,
                            sample.latitude,
                            sample.longitude,
                        )
                }
            val point =
                Point(
                    sample = sample,
                    segment = index,
                    distanceM = distance,
                    elapsedMs = (sample.elapsedRealtimeMs - origin).coerceAtLeast(0),
                    // An altitude that is not a number is not an altitude: the store accepts it (a platform
                    // can report it), and a NaN in the profile would break the whole analysis.
                    altitudeM = sample.altitudeM?.takeIf { it.isFinite() },
                )
            if (previous != null) {
                val legMs = point.elapsedMs - previous.elapsedMs
                val legM = point.distanceM - previous.distanceM
                val speed = if (legMs > 0) legM / (legMs / 1000.0) else 0.0
                legs +=
                    Leg(
                        startIndex = points.size - 1,
                        endIndex = points.size,
                        startDistanceM = previous.distanceM,
                        endDistanceM = point.distanceM,
                        startElapsedMs = previous.elapsedMs,
                        endElapsedMs = point.elapsedMs,
                        durationMs = legMs,
                        movingMs = if (speed >= STATIONARY_SPEED_MPS) legMs else 0L,
                    )
            }
            cumulative = point.distanceM
            points += point
            previous = point
        }
    }
    return Track(points, legs)
}
