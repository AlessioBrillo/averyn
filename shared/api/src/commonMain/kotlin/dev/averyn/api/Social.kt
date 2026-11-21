package dev.averyn.api

import kotlinx.serialization.Serializable

/**
 * The contract of the social foundation (TDD-0018, ADR-0036). Users are opaque ids in every response; a handle only
 * resolves to an id. Nothing here carries a heart-rate, RR, HRV or stress value (ADR-0013): a non-owner view is
 * [PublicActivity], which has no such field.
 */
object Visibilities {
    const val PRIVATE = "private"
    const val FOLLOWERS = "followers"
    const val PUBLIC = "public"
    const val LINK = "link"
    val ALL = setOf(PRIVATE, FOLLOWERS, PUBLIC, LINK)

    /** `link` needs a token made per activity, so it cannot be a default. */
    val DEFAULTABLE = setOf(PRIVATE, FOLLOWERS, PUBLIC)
}

/**
 * The public side of an account: what the owner edits (`GET`/`PUT /v1/me/public-profile`). The rules are checked by
 * [problem], not by an `init` block, for the reason given at [UserProfile].
 */
@Serializable
data class SocialProfile(
    /** Lower case; null while the account has none (then it has no public page). */
    val handle: String? = null,
    val displayName: String? = null,
    val bio: String? = null,
    val sport: String? = null,
    /** A private profile shows only the handle and the name to people who do not follow. */
    val profilePrivate: Boolean = false,
    /** The visibility new activities start with; only the owner changes it, never for an existing activity. */
    val defaultVisibility: String = Visibilities.PRIVATE,
    /** Sent by the client: the user said they are 16 or older. Needed to set a handle. */
    val ageConfirmed: Boolean = false,
    /** Output only. */
    val hasAvatar: Boolean = false,
    /** Output only: the account's own opaque id. */
    val id: String? = null,
) {
    /** Why this profile cannot be stored, or null when it can. */
    fun problem(): String? =
        when {
            handle != null && !HANDLE.matches(handle) -> "handle must be 3-30 characters of a-z, 0-9, _ and ."
            handle != null && handle in RESERVED_HANDLES -> "handle is reserved"
            handle != null && !ageConfirmed -> "a handle needs the confirmation of an age of 16 or older"
            displayName != null && displayName.trim().length !in 1..MAX_NAME ->
                "displayName must be 1-$MAX_NAME characters"
            (bio?.length ?: 0) > MAX_BIO -> "bio is longer than $MAX_BIO characters"
            (sport?.length ?: 0) > MAX_SPORT -> "sport is longer than $MAX_SPORT characters"
            defaultVisibility !in Visibilities.DEFAULTABLE -> "defaultVisibility must be private, followers or public"
            else -> null
        }

    companion object {
        val HANDLE = Regex("[a-z0-9_.]{3,30}")
        const val MAX_NAME = 40
        const val MAX_BIO = 160
        const val MAX_SPORT = 30
        const val HANDLE_CHANGE_DAYS = 30L
        val RESERVED_HANDLES =
            (
                "admin administrator averyn support help root system moderator mod me null undefined api www " +
                    "staff official security abuse privacy"
            ).split(' ').toSet()
    }
}

/** How the viewer stands to a person. `blocked` is a person the viewer blocked; the reverse is never revealed. */
object Relations {
    const val SELF = "self"
    const val NONE = "none"
    const val REQUESTED = "requested"
    const val FOLLOWING = "following"
    const val BLOCKED = "blocked"
}

/** A person in a list: enough to draw a row. */
@Serializable
data class UserRef(
    val id: String,
    val handle: String?,
    val displayName: String?,
    val hasAvatar: Boolean,
)

/** The card of a profile, as a viewer is allowed to see it. */
@Serializable
data class UserCard(
    val id: String,
    val handle: String?,
    val displayName: String?,
    /** Null when the profile is private and the viewer does not follow. */
    val bio: String?,
    val sport: String?,
    val hasAvatar: Boolean,
    val relation: String,
    /** True when the profile is private and the viewer sees only the name. */
    val restricted: Boolean,
)

/** An activity as a non-owner sees it: the summary only, no health value, no analysis series. */
@Serializable
data class PublicActivity(
    val id: String,
    val ownerId: String,
    val sport: String,
    val startedAt: String,
    val distanceM: Double,
    val elapsedMs: Long,
    val movingMs: Long,
    val averageSpeedMps: Double,
    val paceSecPerKm: Double?,
)

@Serializable
data class PublicActivityPage(
    val items: List<PublicActivity>,
    val nextCursor: String?,
)

@Serializable
data class VisibilityRequest(
    val visibility: String,
)

/** The visibility after a change; [shareToken] only for `link`. */
@Serializable
data class VisibilityResult(
    val visibility: String,
    val shareToken: String? = null,
)

@Serializable
data class FollowRequests(
    val count: Int,
    val items: List<UserRef>,
)

@Serializable
data class ReportRequest(
    val targetUserId: String,
    val targetActivityId: String? = null,
    val reason: String,
    val note: String? = null,
) {
    fun problem(): String? =
        when {
            reason !in REASONS -> "reason must be one of ${REASONS.joinToString()}"
            (note?.length ?: 0) > MAX_NOTE -> "note is longer than $MAX_NOTE characters"
            else -> null
        }

    companion object {
        val REASONS = listOf("spam", "abuse", "impersonation", "privacy", "minor", "illegal", "other")
        const val MAX_NOTE = 500
    }
}

/** The relation after a follow or a block call. */
@Serializable
data class RelationResult(
    val relation: String,
)
