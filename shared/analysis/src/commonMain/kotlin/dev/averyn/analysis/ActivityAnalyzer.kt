package dev.averyn.analysis

import dev.averyn.domain.LocationSample
import dev.averyn.domain.Sport
import dev.averyn.metrics.ActivitySnapshot
import dev.averyn.metrics.STATIONARY_SPEED_MPS
import dev.averyn.metrics.haversineMeters
import dev.averyn.sensors.HR_ALGORITHM_VERSION
import dev.averyn.sensors.HeartRateMetrics
import dev.averyn.sensors.HeartRateSample
import kotlin.math.roundToInt

/** A split is 1 km, 5 km for a ride: a kilometer every 100 m is unreadable on a long ride. */
fun splitLengthMeters(sport: Sport): Double = if (sport == Sport.RIDE) 5_000.0 else 1_000.0

private const val ELEVATION_WINDOW = 5

/** How much the altitude has to change before it counts as a climb or a descent (docs/metrics/elevation.md). */
private const val ELEVATION_THRESHOLD_M = 3.0

/** Most points of the display series. The numbers do not depend on it: the chart does. */
private const val MAX_SERIES_POINTS = 2000

private const val MAX_HEART_RATE_POINTS = 1000

/** Two heart-rate samples further apart than this are not the same stretch: the sensor was silent in between. */
private const val HR_MAX_GAP_MS = 2_000L

private const val HR_MIN_BPM = 1

private const val HR_MAX_BPM = 240

/** One point of the track, with the distance from the start and the time from the first sample. */
private class Point(
    val sample: LocationSample,
    val segment: Int,
    val distanceM: Double,
    val elapsedMs: Long,
)

/** One stretch between two accepted samples: the unit the distance and the time are counted in. */
private class Leg(
    val startDistanceM: Double,
    val endDistanceM: Double,
    val startElapsedMs: Long,
    val endElapsedMs: Long,
    val moving: Boolean,
)

/** A split while it is being built. */
private class SplitBuilder(
    val index: Int,
    val startDistanceM: Double,
    val startElapsedMs: Long,
) {
    var endDistanceM: Double = startDistanceM
    var endElapsedMs: Long = startElapsedMs
    var movingMs: Double = 0.0
}

/**
 * The analysis of one activity: the automatic splits, the elevation and the display series.
 *
 * The input is what the shared replay produced: the accepted samples of every continuous recording interval
 * (a pause is a boundary, never a straight line across it), the heart-rate samples recorded while recording,
 * and the totals of the activity.
 *
 * The result is a function of that input alone: the same file always produces the same analysis.
 */
fun analyzeActivity(
    sport: Sport,
    segments: List<List<LocationSample>>,
    heartRate: List<HeartRateSample>,
    totals: ActivitySnapshot,
): ActivityAnalysis {
    val splitLength = splitLengthMeters(sport)
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
                )
            if (previous != null) {
                val legMs = point.elapsedMs - previous.elapsedMs
                val legM = point.distanceM - previous.distanceM
                val speed = if (legMs > 0) legM / (legMs / 1000.0) else 0.0
                legs +=
                    Leg(
                        startDistanceM = previous.distanceM,
                        endDistanceM = point.distanceM,
                        startElapsedMs = previous.elapsedMs,
                        endElapsedMs = point.elapsedMs,
                        moving = speed >= STATIONARY_SPEED_MPS,
                    )
            }
            cumulative = point.distanceM
            points += point
            previous = point
        }
    }

    val smoothed = segments.flatMap { segment -> smoothMedian(segment.map { it.altitudeM }) }
    val builders = buildSplits(legs, splitLength, points.firstOrNull()?.elapsedMs ?: 0L)
    val elevation = buildElevation(segments, points, smoothed, builders)
    val heartRateSummary = buildHeartRate(heartRate, totals)
    val splitHeartRate = assignHeartRateToSplits(heartRate, origin, builders)

    val splits =
        builders.mapIndexed { index, builder ->
            val distance = builder.endDistanceM - builder.startDistanceM
            val movingMs = builder.movingMs.roundToInt().toLong()
            val avgSpeed = if (movingMs > 0) distance / (movingMs / 1000.0) else 0.0
            val hrSum = splitHeartRate.sum[index]
            val hrCount = splitHeartRate.count[index]
            AnalysisSplit(
                index = builder.index,
                startDistanceM = builder.startDistanceM,
                endDistanceM = builder.endDistanceM,
                elapsedMs = (builder.endElapsedMs - builder.startElapsedMs).coerceAtLeast(0),
                movingMs = movingMs,
                avgSpeedMps = avgSpeed,
                elevationGainM = elevation?.perSplitGain?.get(index),
                elevationLossM = elevation?.perSplitLoss?.get(index),
                avgHrBpm = if (hrCount > 0) (hrSum / hrCount).roundToInt() else null,
                partial = distance < splitLength - 1e-6,
            )
        }

    return ActivityAnalysis(
        schemaVersion = ANALYSIS_SCHEMA_VERSION,
        algorithmVersions =
            AnalysisAlgorithmVersions(
                splits = SPLITS_ALGORITHM_VERSION,
                elevation = ELEVATION_ALGORITHM_VERSION,
                series = SERIES_ALGORITHM_VERSION,
                heartRate = HR_ALGORITHM_VERSION,
            ),
        splitLengthM = splitLength,
        totals =
            AnalysisTotals(
                distanceM = totals.distanceM,
                elapsedMs = totals.elapsedMs,
                movingMs = totals.movingMs,
                pausedMs = totals.pausedMs,
                stationaryMs = totals.stationaryMs,
            ),
        elevation = elevation?.summary,
        heartRate = heartRateSummary,
        splits = splits,
        series = buildSeries(points, smoothed, heartRate, origin),
    )
}

/**
 * The automatic splits: a new one every [splitLength] metres of track. The boundary falls inside the leg that
 * crosses it, and the time of the boundary is interpolated there, so the splits add up to the whole track.
 */
private fun buildSplits(
    legs: List<Leg>,
    splitLength: Double,
    firstElapsedMs: Long,
): List<SplitBuilder> {
    val builders = mutableListOf<SplitBuilder>()
    var current = SplitBuilder(1, 0.0, firstElapsedMs)
    var nextBoundary = splitLength
    for (leg in legs) {
        val legDistance = leg.endDistanceM - leg.startDistanceM
        val legElapsed = (leg.endElapsedMs - leg.startElapsedMs).toDouble()
        val legMoving = if (leg.moving) legElapsed else 0.0
        if (legDistance <= 0.0) {
            // A leg with no distance (two samples in the same place) moves no split boundary.
            current.movingMs += legMoving
            current.endDistanceM = leg.endDistanceM
            current.endElapsedMs = leg.endElapsedMs
            continue
        }
        var fraction = 0.0
        while (fraction < 1.0) {
            val currentDistance = leg.startDistanceM + legDistance * fraction
            val toBoundary = nextBoundary - currentDistance
            val remaining = legDistance * (1.0 - fraction)
            if (toBoundary > remaining) {
                // The rest of the leg is inside the current split.
                current.movingMs += legMoving * (1.0 - fraction)
                current.endDistanceM = leg.endDistanceM
                current.endElapsedMs = leg.endElapsedMs
                fraction = 1.0
            } else {
                val boundaryFraction = fraction + toBoundary / legDistance
                current.movingMs += legMoving * (boundaryFraction - fraction)
                current.endDistanceM = nextBoundary
                current.endElapsedMs = leg.startElapsedMs + (legElapsed * boundaryFraction).toLong()
                builders += current
                current = SplitBuilder(builders.size + 1, nextBoundary, current.endElapsedMs)
                nextBoundary += splitLength
                fraction = boundaryFraction
            }
        }
    }
    // A split with no distance is not a split: the activity ended exactly on a boundary.
    if (current.endDistanceM > current.startDistanceM) builders += current
    return builders
}

/** The elevation of the activity and of every split. Null lists when the activity has no altitude at all. */
private class Elevation(
    val summary: AnalysisElevation?,
    val perSplitGain: List<Double>?,
    val perSplitLoss: List<Double>?,
)

/**
 * The elevation: the altitude of the samples, smoothed with a median over five, and the climb and the descent
 * counted with a 3 m threshold so that the noise of the altitude is not a mountain.
 */
private fun buildElevation(
    segments: List<List<LocationSample>>,
    points: List<Point>,
    smoothed: List<Double?>,
    builders: List<SplitBuilder>,
): Elevation {
    val perSplitGain = DoubleArray(builders.size)
    val perSplitLoss = DoubleArray(builders.size)
    var totalGain = 0.0
    var totalLoss = 0.0
    var splitPointer = 0
    var index = 0
    for (segment in segments) {
        // A pause breaks the profile: the altitude before and after it are not compared.
        var reference: Double? = null
        for (position in segment.indices) {
            val altitude = smoothed[index + position]
            if (altitude != null) {
                val current = reference
                if (current == null) {
                    reference = altitude
                } else if (altitude - current > ELEVATION_THRESHOLD_M) {
                    val climb = altitude - current
                    totalGain += climb
                    if (builders.isNotEmpty()) {
                        while (splitPointer + 1 < builders.size &&
                            points[index + position].distanceM >= builders[splitPointer].endDistanceM
                        ) {
                            splitPointer++
                        }
                        perSplitGain[splitPointer] += climb
                    }
                    reference = altitude
                } else if (current - altitude > ELEVATION_THRESHOLD_M) {
                    val descent = current - altitude
                    totalLoss += descent
                    if (builders.isNotEmpty()) {
                        while (splitPointer + 1 < builders.size &&
                            points[index + position].distanceM >= builders[splitPointer].endDistanceM
                        ) {
                            splitPointer++
                        }
                        perSplitLoss[splitPointer] += descent
                    }
                    reference = altitude
                }
            }
        }
        index += segment.size
    }
    val measured = smoothed.filterNotNull()
    if (measured.isEmpty()) {
        // No altitude at all: the activity has no elevation, not a flat one.
        return Elevation(null, null, null)
    }
    val summary =
        AnalysisElevation(
            source = ELEVATION_SOURCE_RECORDED,
            gainM = totalGain,
            lossM = totalLoss,
            minM = measured.min(),
            maxM = measured.max(),
            avgM = measured.sum() / measured.size,
            coveragePercent = 100.0 * measured.size / points.size.coerceAtLeast(1),
        )
    return Elevation(summary, perSplitGain.toList(), perSplitLoss.toList())
}

/** The heart rate of the activity, `hr-v2`: the coverage is over the recording time, the pauses excluded. */
private fun buildHeartRate(
    heartRate: List<HeartRateSample>,
    totals: ActivitySnapshot,
): AnalysisHeartRate? {
    if (heartRate.isEmpty()) return null
    val metrics = HeartRateMetrics()
    heartRate.forEach { metrics.onSample(it.bpm) }
    val recordingMs = (totals.elapsedMs - totals.pausedMs).coerceAtLeast(0)
    val snapshot = metrics.snapshot(recordingMs)
    return AnalysisHeartRate(
        minBpm = snapshot.minHrBpm,
        avgBpm = snapshot.avgHrBpm,
        maxBpm = snapshot.maxHrBpm,
        coveragePercent = snapshot.coveragePercent,
        dropouts = snapshot.dropouts,
        artifacts = snapshot.artifacts,
    )
}

/** The mean heart rate of every split, from the samples that fall in its time range. */
private class SplitHeartRate(
    val sum: DoubleArray,
    val count: IntArray,
)

private fun assignHeartRateToSplits(
    heartRate: List<HeartRateSample>,
    origin: Long,
    builders: List<SplitBuilder>,
): SplitHeartRate {
    val sum = DoubleArray(builders.size)
    val count = IntArray(builders.size)
    if (builders.isEmpty()) return SplitHeartRate(sum, count)
    var pointer = 0
    for (sample in heartRate) {
        if (sample.bpm !in HR_MIN_BPM..HR_MAX_BPM) continue
        val elapsed = (sample.elapsedRealtimeMs - origin).coerceAtLeast(0)
        val last = builders.last()
        if (elapsed > last.endElapsedMs) continue
        while (pointer + 1 < builders.size && elapsed >= builders[pointer].endElapsedMs) pointer++
        sum[pointer] += sample.bpm
        count[pointer]++
    }
    return SplitHeartRate(sum, count)
}

/** The display series: the track, the elevation and the heart rate, decimated to a bounded number of points. */
private fun buildSeries(
    points: List<Point>,
    smoothed: List<Double?>,
    heartRate: List<HeartRateSample>,
    origin: Long,
): AnalysisSeries {
    val count = points.size
    val distances = DoubleArray(count)
    val altitudes = DoubleArray(count)
    var carried = 0.0
    for (i in 0 until count) {
        distances[i] = points[i].distanceM
        val altitude = smoothed.getOrNull(i)
        if (altitude != null) carried = altitude
        altitudes[i] = carried
    }
    val kept = decimateLttb(distances, altitudes, MAX_SERIES_POINTS)
    return AnalysisSeries(
        distanceM = kept.map { distances[it] },
        elevationM = kept.map { smoothed.getOrNull(it) },
        elapsedMs = kept.map { points[it].elapsedMs },
        segment = kept.map { points[it].segment },
        heartRate = buildHeartRateSeries(heartRate, origin),
    )
}

/**
 * The heart rate over time. The samples are grouped in stretches: where the sensor was silent for more than two
 * seconds the line breaks, because the gap is a dropout and not a flat heart rate.
 */
private fun buildHeartRateSeries(
    heartRate: List<HeartRateSample>,
    origin: Long,
): AnalysisHeartRateSeries {
    val usable = heartRate.filter { it.bpm in HR_MIN_BPM..HR_MAX_BPM }
    if (usable.isEmpty()) return AnalysisHeartRateSeries(emptyList(), emptyList(), emptyList())
    val groups = mutableListOf<MutableList<HeartRateSample>>()
    var previous: HeartRateSample? = null
    for (sample in usable) {
        val last = previous
        if (last == null || sample.elapsedRealtimeMs - last.elapsedRealtimeMs > HR_MAX_GAP_MS) {
            groups.add(mutableListOf())
        }
        groups.last().add(sample)
        previous = sample
    }
    val elapsed = mutableListOf<Long>()
    val bpm = mutableListOf<Int>()
    val segment = mutableListOf<Int>()
    groups.forEachIndexed { index, group ->
        val budget = maxOf(2, MAX_HEART_RATE_POINTS * group.size / usable.size)
        val kept =
            if (group.size <= budget) {
                group.indices.toList()
            } else {
                val xs = DoubleArray(group.size) { group[it].elapsedRealtimeMs.toDouble() }
                val ys = DoubleArray(group.size) { group[it].bpm.toDouble() }
                decimateLttb(xs, ys, budget).toList()
            }
        kept.forEach { position ->
            elapsed.add((group[position].elapsedRealtimeMs - origin).coerceAtLeast(0))
            bpm.add(group[position].bpm)
            segment.add(index)
        }
    }
    return AnalysisHeartRateSeries(elapsedMs = elapsed, bpm = bpm, segment = segment)
}

/** The median of the altitude over a window of five points: one wrong altitude does not move the profile. */
private fun smoothMedian(altitudes: List<Double?>): List<Double?> {
    val n = altitudes.size
    if (n == 0) return emptyList()
    val half = ELEVATION_WINDOW / 2
    return List(n) { index ->
        val from = (index - half).coerceAtLeast(0)
        val to = (index + half).coerceAtMost(n - 1)
        val window = altitudes.subList(from, to + 1).filterNotNull()
        if (window.isEmpty()) null else median(window)
    }
}

private fun median(values: List<Double>): Double {
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
}
