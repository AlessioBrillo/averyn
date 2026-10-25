package dev.averyn.analysis

import kotlinx.serialization.Serializable

const val HR_ZONES_ALGORITHM_VERSION = "hr-zones-v1"

/** The lower limit of zones 2 to 5, in percent of the maximum heart rate (docs/metrics/hr-zones.md). */
private val ZONE_LOWER_PERCENT = intArrayOf(60, 70, 80, 90)

/** One zone: its limits in bpm (`toBpm` is null for the last, open one) and the time spent in it. */
@Serializable
data class HeartRateZone(
    val zone: Int,
    val fromBpm: Int,
    val toBpm: Int?,
    val timeMs: Long,
)

/** The five zones of an activity for a maximum heart rate, and the version of the rule that cut them. */
@Serializable
data class HeartRateZones(
    val algorithmVersion: String,
    val maxHrBpm: Int,
    val zones: List<HeartRateZone>,
)

/**
 * `hr-zones-v1`: five zones by share of [maxHrBpm] (Z1 below 60 %, Z2 from 60 %, Z3 from 70 %, Z4 from 80 %,
 * Z5 from 90 %). The limit of a zone is rounded up to a whole bpm, so a bpm belongs to exactly one zone and the
 * zones add up to the time of the histogram.
 */
fun heartRateZones(
    histogram: AnalysisHeartRateHistogram,
    maxHrBpm: Int,
): HeartRateZones {
    require(maxHrBpm > 0) { "the maximum heart rate must be positive" }
    // The first bpm of zones 2 to 5.
    val lower = ZONE_LOWER_PERCENT.map { (maxHrBpm * it + 99) / 100 }
    val time = LongArray(5)
    histogram.timeMs.forEachIndexed { index, ms ->
        val bpm = histogram.fromBpm + index
        time[lower.count { bpm >= it }] += ms
    }
    return HeartRateZones(
        algorithmVersion = HR_ZONES_ALGORITHM_VERSION,
        maxHrBpm = maxHrBpm,
        zones =
            List(5) { zone ->
                HeartRateZone(
                    zone = zone + 1,
                    fromBpm = if (zone == 0) 0 else lower[zone - 1],
                    toBpm = if (zone == 4) null else lower[zone] - 1,
                    timeMs = time[zone],
                )
            },
    )
}
