package dev.averyn.client

import dev.averyn.analysis.ActivityAnalysis
import dev.averyn.api.ActivityPage
import dev.averyn.api.ActivitySummary
import dev.averyn.api.ClientConfig
import dev.averyn.api.Track

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
}
