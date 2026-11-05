package dev.averyn.backend.privacy

import dev.averyn.analysis.hidingZones
import dev.averyn.api.PrivacyZones
import dev.averyn.api.Track
import kotlinx.serialization.json.Json

private val featureJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

/**
 * The GeoJSON Feature of a track with the privacy zones of its owner applied (TDD-0016). With no zone the string is
 * returned untouched; otherwise it is read, cut and written again. A failure to read it is not allowed to leak the
 * uncut track: it propagates, and the route answers with an error.
 */
fun hideZonesIn(
    featureGeoJson: String,
    zones: PrivacyZones,
): String {
    if (zones.zones.isEmpty()) return featureGeoJson
    val track = featureJson.decodeFromString(Track.serializer(), featureGeoJson)
    return featureJson.encodeToString(Track.serializer(), track.hidingZones(zones.zones))
}
