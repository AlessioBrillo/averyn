package dev.averyn.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * The track of an activity as `GET /v1/activities/{id}/track` returns it: a GeoJSON Feature whose geometry is a
 * MultiLineString, one line per continuous recording interval (a pause ends a line instead of being bridged).
 *
 * The DTO is wire-shaped on purpose: the mobile apps cache it as it is, and the map slice (MapLibre Native) will
 * consume the same coordinates without a conversion in between.
 */
@Serializable
data class Track(
    val type: String = "Feature",
    val properties: JsonObject? = null,
    /** null when the activity has no usable track. */
    val geometry: TrackGeometry? = null,
)

/** The MultiLineString of a [Track]: `[longitude, latitude]` pairs, line by line. */
@Serializable
data class TrackGeometry(
    val type: String = "MultiLineString",
    val coordinates: List<List<List<Double>>> = emptyList(),
)
