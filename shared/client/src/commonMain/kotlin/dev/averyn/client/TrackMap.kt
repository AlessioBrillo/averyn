package dev.averyn.client

import dev.averyn.api.Track
import dev.averyn.api.TrackGeometry
import kotlinx.serialization.json.Json

/**
 * The track of an activity as the phone map needs it (TDD-0012): the GeoJSON both map SDKs read, and the box the
 * camera has to show. Computed once here, so neither app walks the coordinates.
 *
 * The coordinates are the ones of the server's track: `[longitude, latitude]` pairs, one line per recording
 * interval, so a pause is a break in the line and not a straight segment across it.
 */
class TrackMap(
    /** A GeoJSON Feature with a MultiLineString geometry. */
    val geoJson: String,
    val minLatitude: Double,
    val minLongitude: Double,
    val maxLatitude: Double,
    val maxLongitude: Double,
)

private val trackJson =
    Json {
        encodeDefaults = true
    }

/**
 * The map of a track, or null when the activity has no usable track (no geometry, or no valid point at all).
 *
 * The GeoJSON is built from the valid points only: a point that is not a `[lon, lat]` pair in range is dropped
 * (one bad coordinate must not hide the track, nor make the encoder fail), and a line left without points goes.
 */
fun trackMapOf(track: Track?): TrackMap? {
    val lines = track?.geometry?.coordinates ?: return null
    var minLat = Double.POSITIVE_INFINITY
    var maxLat = Double.NEGATIVE_INFINITY
    var minLon = Double.POSITIVE_INFINITY
    var maxLon = Double.NEGATIVE_INFINITY
    val valid = mutableListOf<List<List<Double>>>()
    for (line in lines) {
        val kept = mutableListOf<List<Double>>()
        for (point in line) {
            if (point.size < 2) continue
            val lon = point[0]
            val lat = point[1]
            if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
            if (lat < minLat) minLat = lat
            if (lat > maxLat) maxLat = lat
            if (lon < minLon) minLon = lon
            if (lon > maxLon) maxLon = lon
            kept += listOf(lon, lat)
        }
        if (kept.isNotEmpty()) valid += kept
    }
    if (valid.isEmpty()) return null
    return TrackMap(
        geoJson = trackJson.encodeToString(Track.serializer(), Track(geometry = TrackGeometry(coordinates = valid))),
        minLatitude = minLat,
        minLongitude = minLon,
        maxLatitude = maxLat,
        maxLongitude = maxLon,
    )
}
