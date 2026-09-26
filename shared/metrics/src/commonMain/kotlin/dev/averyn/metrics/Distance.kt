package dev.averyn.metrics

import dev.averyn.domain.LocationSample
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Algorithm version stamped on every stored result. Spec: docs/metrics/distance.md. */
const val DISTANCE_ALGORITHM_VERSION = "distance-v1"

private const val EARTH_RADIUS_M = 6_371_008.8 // IUGG mean radius

/** Great-circle distance in meters between two WGS84 points. */
fun haversineMeters(
    lat1: Double,
    lon1: Double,
    lat2: Double,
    lon2: Double,
): Double {
    val p1 = lat1 * PI / 180
    val p2 = lat2 * PI / 180
    val dp = p2 - p1
    val dl = (lon2 - lon1) * PI / 180
    val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
    return 2 * EARTH_RADIUS_M * asin(sqrt(a))
}

/**
 * `distance-v1`: sum of haversine legs over consecutive samples, no smoothing.
 * The caller passes only *accepted* samples (see the metric spec).
 */
fun distanceMeters(samples: List<LocationSample>): Double =
    samples.zipWithNext().sumOf { (a, b) -> haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude) }
