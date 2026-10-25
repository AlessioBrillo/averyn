package dev.averyn.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The unit system a client draws distances, paces and elevations in (TDD-0012). */
@Serializable
enum class UnitSystem {
    @SerialName("metric")
    METRIC,

    @SerialName("imperial")
    IMPERIAL,
}

/**
 * The settings of an account that every client shares (TDD-0012): the unit system and the maximum heart rate.
 *
 * The rules are checked by [problem], not by an `init` block: a rule in `init` comes out of the serialization
 * library as a decoding error, which is not the `422` the contract promises.
 */
@Serializable
data class UserProfile(
    val units: UnitSystem = UnitSystem.METRIC,
    /** The maximum heart rate in bpm, or null when the user did not say. */
    val maxHrBpm: Int? = null,
) {
    /** Why this profile cannot be stored, or null when it can. */
    fun problem(): String? =
        if (maxHrBpm != null && maxHrBpm !in MIN_MAX_HR_BPM..MAX_MAX_HR_BPM) {
            "maxHrBpm must be between $MIN_MAX_HR_BPM and $MAX_MAX_HR_BPM"
        } else {
            null
        }

    companion object {
        const val MIN_MAX_HR_BPM = 100
        const val MAX_MAX_HR_BPM = 230
    }
}
