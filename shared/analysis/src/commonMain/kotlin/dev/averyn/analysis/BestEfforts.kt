package dev.averyn.analysis

import dev.averyn.domain.LocationSample
import dev.averyn.domain.Sport
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/** Version of the best-effort algorithm. Spec: docs/metrics/best-efforts.md. */
const val BEST_EFFORTS_ALGORITHM_VERSION = "best-efforts-v1"

/**
 * The shortest silence that breaks a best effort, whatever the sampling of the activity is.
 *
 * A recorder samples once a second (TDD-0001), so ten seconds without a sample is ten missing ones. An
 * imported file can sample every five or ten seconds, and the silence that means "the signal was lost" is
 * then longer: the threshold follows the sampling of the activity (see [gapThresholdMs]).
 */
const val BEST_EFFORT_MIN_GAP_MS = 10_000L

/** How much longer than the usual interval a silence has to be to count as a gap. */
private const val GAP_INTERVAL_FACTOR = 5

/**
 * The distances a best effort is measured over, per sport: the standard distances of the discipline.
 *
 * A change of this list is a change of the algorithm, and therefore a new version of it.
 */
fun bestEffortDistancesM(sport: Sport): List<Double> =
    when (sport) {
        Sport.RUN -> listOf(1_000.0, 5_000.0, 10_000.0, 21_097.5, 42_195.0)
        Sport.RIDE -> listOf(5_000.0, 10_000.0, 20_000.0, 40_000.0, 50_000.0, 100_000.0)
        Sport.WALK -> listOf(1_000.0, 5_000.0, 10_000.0)
        Sport.HIKE -> listOf(1_000.0, 5_000.0, 10_000.0)
    }

/**
 * The fastest average speed an effort of the sport can have.
 *
 * A window faster than this is not an athlete, it is a GPS jump: it is skipped, and the next plausible window
 * is used instead. The cap is generous on purpose — it must only catch what no runner and no rider can do.
 */
fun maxPlausibleSpeedMps(sport: Sport): Double =
    when (sport) {
        Sport.RUN -> 12.0
        Sport.RIDE -> 35.0
        Sport.WALK -> 6.0
        Sport.HIKE -> 5.0
    }

/**
 * One best effort of an activity: the fastest stretch of the track that covers exactly [distanceM].
 *
 * It is not a split: a split is a fixed piece of the track, a best effort is the fastest [distanceM] of the
 * whole activity, wherever it happens to be.
 */
@Serializable
data class BestEffort(
    /** The distance of the effort: one of the targets of the sport. */
    val distanceM: Double,
    /** The moving time of the effort: what a record is made of. */
    val durationMs: Long,
    /** The wall time of the effort, the stops inside it included. */
    val elapsedMs: Long,
    /** Where the effort starts, on the cumulative distance of the activity. */
    val startDistanceM: Double,
    /** Where the effort ends, on the cumulative distance of the activity. */
    val endDistanceM: Double,
    /** When the effort starts, from the first sample of the activity. */
    val startElapsedMs: Long,
    /** When the effort ends, from the first sample of the activity. */
    val endElapsedMs: Long,
    val avgSpeedMps: Double,
    /** The pace of the effort; null when nothing moved. */
    val paceSecPerKm: Double?,
)

/**
 * The best efforts of an activity: the fastest window of each target distance of the sport.
 *
 * The result is a function of the input alone: the same file always produces the same best efforts.
 */
fun bestEfforts(
    sport: Sport,
    segments: List<List<LocationSample>>,
): List<BestEffort> = bestEfforts(sport, buildTrack(segments))

/** The best efforts of an activity, from the track that was already built. */
internal fun bestEfforts(
    sport: Sport,
    track: Track,
): List<BestEffort> {
    if (track.points.size < 2) return emptyList()
    val cap = maxPlausibleSpeedMps(sport)
    val targets = bestEffortDistancesM(sport)
    val best = LinkedHashMap<Double, BestEffort>()
    for (run in effortRuns(track)) {
        for (target in targets) {
            val candidate = fastestWindow(run, target, cap) ?: continue
            val current = best[target]
            if (current == null || isBetter(candidate, current)) best[target] = candidate
        }
    }
    return best.values.sortedBy { it.distanceM }
}

/**
 * The fastest window of the activity that covers [target] metres, whatever the targets of the sport are.
 *
 * It is the same search the best efforts use, with the distance of the effort left to the caller: it is what
 * the tests of the algorithm use to pin a window that is not a target of any sport.
 */
internal fun fastestWindowOf(
    sport: Sport,
    segments: List<List<LocationSample>>,
    target: Double,
): BestEffort? {
    val track = buildTrack(segments)
    if (track.points.size < 2) return null
    val cap = maxPlausibleSpeedMps(sport)
    var best: BestEffort? = null
    for (run in effortRuns(track)) {
        val candidate = fastestWindow(run, target, cap) ?: continue
        if (isBetter(candidate, best)) best = candidate
    }
    return best
}

/**
 * A stretch of the track an effort can live in: no pause and no signal gap inside it.
 *
 * A pause is already a boundary between two recording intervals. A signal gap is not: it is a long leg inside
 * an interval, and an effort that crossed it would be measuring the hole instead of the athlete.
 */
private class Run(
    /** The cumulative distance of every point of the run. */
    val distances: DoubleArray,
    /** The time of every point of the run, from the first sample of the activity. */
    val elapsed: LongArray,
    /** The moving time from the first point of the run to every point of it. */
    val movingPrefix: LongArray,
    /** The legs of the run: the leg [i] is the one between the points [i] and [i + 1]. */
    val legs: List<Leg>,
) {
    val size: Int get() = distances.size
}

private fun effortRuns(track: Track): List<Run> {
    val runs = mutableListOf<Run>()
    val gapMs = gapThresholdMs(track)
    var points = mutableListOf<Point>()
    var legs = mutableListOf<Leg>()
    var previous: Leg? = null
    for (leg in track.legs) {
        val continues =
            previous != null &&
                previous.endIndex == leg.startIndex &&
                leg.durationMs <= gapMs
        if (!continues) {
            if (points.size >= 2) runs += runOf(points, legs)
            points = mutableListOf()
            legs = mutableListOf()
        }
        if (points.isEmpty()) points += track.points[leg.startIndex]
        points += track.points[leg.endIndex]
        legs += leg
        previous = leg
    }
    if (points.size >= 2) runs += runOf(points, legs)
    return runs
}

/**
 * The silence that means "the signal was lost", for this activity.
 *
 * It is the longer of ten seconds and five times the usual interval between two samples: a recorder samples
 * once a second and a ten-second silence is ten missing samples, while an imported file that samples every
 * ten seconds needs a longer silence before it is a gap and not the sampling itself.
 */
private fun gapThresholdMs(track: Track): Long {
    val intervals =
        track.legs
            .map { it.durationMs }
            .filter { it > 0 }
            .sorted()
    if (intervals.isEmpty()) return BEST_EFFORT_MIN_GAP_MS
    val median = intervals[intervals.size / 2]
    return maxOf(BEST_EFFORT_MIN_GAP_MS, median * GAP_INTERVAL_FACTOR)
}

private fun runOf(
    points: List<Point>,
    legs: List<Leg>,
): Run {
    val distances = DoubleArray(points.size) { points[it].distanceM }
    val elapsed = LongArray(points.size) { points[it].elapsedMs }
    val movingPrefix = LongArray(points.size)
    legs.forEachIndexed { index, leg -> movingPrefix[index + 1] = movingPrefix[index] + leg.movingMs }
    return Run(distances, elapsed, movingPrefix, legs)
}

/**
 * The fastest window of the run that covers [target] metres, or null when there is none.
 *
 * The window is found twice, and the best of the two is kept:
 *
 * - the window **starts on a sample** and ends inside the leg that crosses the target;
 * - the window **ends on a sample** and starts inside the leg that crosses the target.
 *
 * One of the two is enough when the speed does not change inside the window, but not in general: the time to
 * cover a distance is the shortest when the window is as fast as possible, and the window that does that can
 * start in the middle of a leg. Both families are needed to find it, and the search is linear in the number of
 * points.
 */
private fun fastestWindow(
    run: Run,
    target: Double,
    cap: Double,
): BestEffort? {
    val n = run.size
    if (n < 2) return null
    var best: BestEffort? = null

    // The window starts on a sample; the end falls inside the leg that crosses the target.
    var end = 1
    for (start in 0 until n - 1) {
        if (end <= start) end = start + 1
        while (end < n && run.distances[end] - run.distances[start] < target) end++
        if (end >= n) break
        val leg = run.legs[end - 1]
        val fraction = (target - (run.distances[end - 1] - run.distances[start])) / leg.distanceM
        val moving = (run.movingPrefix[end - 1] - run.movingPrefix[start]) + fraction * leg.movingMs
        val elapsed = (run.elapsed[end - 1] - run.elapsed[start]) + fraction * leg.durationMs
        val candidate =
            candidate(
                target,
                moving,
                elapsed,
                run.distances[start],
                run.distances[start] + target,
                run.elapsed[start].toDouble(),
                run.elapsed[end - 1] + fraction * leg.durationMs,
                cap,
            )
        if (candidate != null && isBetter(candidate, best)) best = candidate
    }

    // The window ends on a sample; the start falls inside the leg that crosses the target.
    var start = 0
    for (endIndex in 1 until n) {
        while (start + 1 <= endIndex - 1 && run.distances[endIndex] - run.distances[start + 1] >= target) start++
        if (run.distances[endIndex] - run.distances[start] < target) continue
        val leg = run.legs[start]
        val from = run.distances[endIndex] - target
        val fraction = (from - run.distances[start]) / leg.distanceM
        val moving = (run.movingPrefix[endIndex] - run.movingPrefix[start + 1]) + (1 - fraction) * leg.movingMs
        val elapsed = (run.elapsed[endIndex] - run.elapsed[start + 1]) + (1 - fraction) * leg.durationMs
        val candidate =
            candidate(
                target,
                moving,
                elapsed,
                from,
                run.distances[endIndex],
                run.elapsed[start] + fraction * leg.durationMs,
                run.elapsed[endIndex].toDouble(),
                cap,
            )
        if (candidate != null && isBetter(candidate, best)) best = candidate
    }
    return best
}

/** The window as a best effort, when it is one: it must move, and it must be plausible. */
private fun candidate(
    distanceM: Double,
    moving: Double,
    elapsed: Double,
    startDistanceM: Double,
    endDistanceM: Double,
    startElapsedMs: Double,
    endElapsedMs: Double,
    cap: Double,
): BestEffort? {
    val durationMs = moving.roundToInt().toLong()
    if (durationMs <= 0) return null
    val speed = distanceM / (durationMs / 1000.0)
    if (speed > cap) return null
    return BestEffort(
        distanceM = distanceM,
        durationMs = durationMs,
        elapsedMs = elapsed.roundToInt().toLong(),
        startDistanceM = startDistanceM,
        endDistanceM = endDistanceM,
        startElapsedMs = startElapsedMs.roundToInt().toLong(),
        endElapsedMs = endElapsedMs.roundToInt().toLong(),
        avgSpeedMps = speed,
        paceSecPerKm = if (speed > 0) 1000.0 / speed else null,
    )
}

/** The faster one wins; a tie goes to the one that starts earlier. */
private fun isBetter(
    candidate: BestEffort,
    current: BestEffort?,
): Boolean {
    if (current == null) return true
    if (candidate.durationMs != current.durationMs) return candidate.durationMs < current.durationMs
    return candidate.startElapsedMs < current.startElapsedMs
}
