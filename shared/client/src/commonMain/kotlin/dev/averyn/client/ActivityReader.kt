package dev.averyn.client

import dev.averyn.analysis.ActivityAnalysis
import dev.averyn.api.ActivityPage
import dev.averyn.api.ActivitySummary
import dev.averyn.api.ClientConfig
import dev.averyn.api.ConsentGrant
import dev.averyn.api.Track
import dev.averyn.api.UserProfile

/**
 * What the repository needs from the server (TDD-0011).
 *
 * The interface exists so that the merge of the server list and the local store can be tested without a
 * network: the tests give the repository a reader that answers what they want.
 */
interface ActivityReader {
    /** The public configuration of the server. */
    suspend fun clientConfig(): ApiResult<ClientConfig>

    /** One page of the activity list, newest first. */
    suspend fun listActivities(
        accessToken: String,
        cursor: String? = null,
        limit: Int = 50,
    ): ApiResult<ActivityPage>

    /** One activity of the account. */
    suspend fun activity(
        accessToken: String,
        id: String,
    ): ApiResult<ActivitySummary>

    /** The track of an activity, as GeoJSON. */
    suspend fun track(
        accessToken: String,
        id: String,
    ): ApiResult<Track>

    /** The analysis of an activity. */
    suspend fun analysis(
        accessToken: String,
        id: String,
    ): ApiResult<ActivityAnalysis>

    /** The caller's active consent grants (ADR-0021). Fail-closed: an empty list when the server
     *  cannot be reached or answers unsuccessfully. */
    suspend fun consents(accessToken: String): ApiResult<List<ConsentGrant>>

    /** The profile of the account: the unit system and the maximum heart rate (TDD-0012). */
    suspend fun profile(accessToken: String): ApiResult<UserProfile>

    /**
     * Deletes the account and everything the server holds for it (ADR-0017, TDD-0004). Repeating it is harmless: a
     * deleted account answers `204` again, which is why a lost answer can be asked again.
     */
    suspend fun deleteAccount(accessToken: String): ApiResult<Unit>

    /** Replaces the profile; the answer is the profile the server stored. A `422` means it was not valid. */
    suspend fun saveProfile(
        accessToken: String,
        profile: UserProfile,
    ): ApiResult<UserProfile>
}
