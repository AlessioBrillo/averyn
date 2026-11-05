package dev.averyn.analysis

import dev.averyn.api.PrivacyZone
import dev.averyn.api.Track
import dev.averyn.api.TrackGeometry
import dev.averyn.metrics.haversineMeters

/**
 * The track as it is shown: every point inside a privacy zone is hidden (TDD-0016). A hidden stretch breaks the line,
 * so the map never draws a segment across a zone (that would point at it), and a piece left with a single point goes
 * too. Nothing is stored: this is a view of the track, and the recorded samples are untouched.
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
        for (point in line) {
            val hidden = point.size >= 2 && zones.any { it.contains(lat = point[1], lon = point[0]) }
            if (hidden) close() else piece += point
        }
        close()
    }
    return copy(geometry = if (shown.isEmpty()) null else TrackGeometry(coordinates = shown))
}

private fun PrivacyZone.contains(
    lat: Double,
    lon: Double,
): Boolean = haversineMeters(latitude, longitude, lat, lon) <= radiusM
