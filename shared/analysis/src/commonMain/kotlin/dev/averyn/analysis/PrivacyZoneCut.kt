package dev.averyn.analysis

import dev.averyn.api.PrivacyZone
import dev.averyn.api.Track
import dev.averyn.api.TrackGeometry
import dev.averyn.metrics.haversineMeters
import kotlin.math.PI
import kotlin.math.cos

/**
 * The track as it is shown: every point inside a privacy zone is hidden (TDD-0016). A hidden stretch breaks the line,
 * so the map never draws a segment across a zone (that would point at it), and a piece left with a single point goes
 * too. A segment between two visible points that passes through a zone is hidden as well: a sparse file (a watch's
 * smart recording, a coarse GPX) has long segments whose ends are outside while the line crosses the zone. Nothing is stored: this is a view of the track, and the recorded samples are untouched.
 *
 * Returns the same track when there are no zones; a track whose every point is hidden has no geometry (the "no usable
 * track" answer the clients already handle).
 */
fun Track.hidingZones(zones: List<PrivacyZone>): Track {
    if (zones.isEmpty()) return this
    val lines = geometry?.coordinates ?: return this
    val shown = mutableListOf<List<List<Double>>>()
    for (line in lines) {
        var piece = mutableListOf<List<Double>>()

        fun close() {
            if (piece.size >= 2) shown += piece
            piece = mutableListOf()
        }
        var previous: List<Double>? = null
        for (point in line) {
            val hidden = point.size >= 2 && zones.any { it.contains(lat = point[1], lon = point[0]) }
            if (hidden) {
                close()
                previous = null
            } else {
                val from = previous
                if (from != null && zones.any { it.crosses(from, point) }) close()
                piece += point
                previous = point
            }
        }
        close()
    }
    return copy(geometry = if (shown.isEmpty()) null else TrackGeometry(coordinates = shown))
}

private fun PrivacyZone.contains(
    lat: Double,
    lon: Double,
): Boolean = haversineMeters(latitude, longitude, lat, lon) <= radiusM

/**
 * Whether the straight segment between two points comes within the radius of the zone. Local flat projection around
 * the zone's centre (metres east and north): exact enough for a radius of at most a few kilometres.
 */
private fun PrivacyZone.crosses(
    from: List<Double>,
    to: List<Double>,
): Boolean {
    if (from.size < 2 || to.size < 2) return false
    val metresPerDegree = EARTH_RADIUS_M * PI / 180.0
    val east = cos(latitude * PI / 180.0) * metresPerDegree
    val ax = (from[0] - longitude) * east
    val ay = (from[1] - latitude) * metresPerDegree
    val bx = (to[0] - longitude) * east
    val by = (to[1] - latitude) * metresPerDegree
    val dx = bx - ax
    val dy = by - ay
    val lengthSquared = dx * dx + dy * dy
    // The point of the segment nearest to the centre (the origin): the foot of the perpendicular, kept on the segment.
    val t = if (lengthSquared == 0.0) 0.0 else ((-ax * dx - ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
    val nx = ax + t * dx
    val ny = ay + t * dy
    return nx * nx + ny * ny <= radiusM.toDouble() * radiusM
}

private const val EARTH_RADIUS_M = 6_371_000.0
