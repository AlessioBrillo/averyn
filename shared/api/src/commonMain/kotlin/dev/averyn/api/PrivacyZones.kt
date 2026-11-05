package dev.averyn.api

import kotlinx.serialization.Serializable

/**
 * A place the user does not want to show (home, work): a circle. The part of a track inside it is hidden on every map
 * and card (TDD-0016); the recorded file and the private export keep every sample.
 */
@Serializable
data class PrivacyZone(
    val latitude: Double,
    val longitude: Double,
    /** The radius in metres. */
    val radiusM: Int,
    /** A name for the user's own list ("Home"); never shown on a map. */
    val label: String? = null,
)

/**
 * The zones of an account, replaced as a whole. The rules are checked by [problem], not by an `init` block, for the
 * reason given at [UserProfile]: a rule in `init` is a decoding error and not the `422` the contract promises.
 */
@Serializable
data class PrivacyZones(
    val zones: List<PrivacyZone> = emptyList(),
) {
    /** Why these zones cannot be stored, or null when they can. */
    fun problem(): String? {
        if (zones.size > MAX_ZONES) return "at most $MAX_ZONES zones"
        for (zone in zones) {
            if (!zone.latitude.isFinite() || zone.latitude !in -90.0..90.0) return "latitude out of range"
            if (!zone.longitude.isFinite() || zone.longitude !in -180.0..180.0) return "longitude out of range"
            if (zone.radiusM !in
                MIN_RADIUS_M..MAX_RADIUS_M
            ) {
                return "radiusM must be between $MIN_RADIUS_M and $MAX_RADIUS_M"
            }
            if ((zone.label?.length ?: 0) > MAX_LABEL_LENGTH) return "label is longer than $MAX_LABEL_LENGTH characters"
        }
        return null
    }

    companion object {
        const val MAX_ZONES = 10

        /** Below 50 m a zone hides almost nothing: the first fix outside it is the front door. */
        const val MIN_RADIUS_M = 50
        const val MAX_RADIUS_M = 2000
        const val MAX_LABEL_LENGTH = 40
    }
}
