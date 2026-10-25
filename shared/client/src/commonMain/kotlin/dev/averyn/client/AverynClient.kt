package dev.averyn.client

import dev.averyn.analysis.ActivityAnalysis
import dev.averyn.api.ActivityPage
import dev.averyn.api.ActivitySummary
import dev.averyn.api.ClientConfig
import dev.averyn.api.ConsentGrant
import dev.averyn.api.Track
import dev.averyn.api.UserProfile
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * The read path of the server (TDD-0011): the list of the activities, one activity, its track and its analysis.
 *
 * Every call is a GET, so every call is safe to repeat; the one exception is the save of the profile, a PUT that
 * replaces the whole document, which is just as safe to repeat. A temporary failure is repeated at most
 * [MAX_RETRIES] times, waiting as long as the server asks (`Retry-After`); the analysis is the exception: a
 * `503` there means "it is being computed", not "it failed", and it is reported as such instead of being
 * retried in a loop.
 */
class AverynClient(
    serverUrl: String,
    private val http: HttpClient = defaultHttpClient(),
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val nowMs: () -> Long = { currentTimeMillis() },
) : ActivityReader {
    private val baseUrl = serverUrl.trimEnd('/')

    /** The public configuration of the server: the issuer, the client id and the versions (TDD-0011). */
    override suspend fun clientConfig(): ApiResult<ClientConfig> =
        get(path = "/v1/client-config", token = null) { json.decodeFromString(ClientConfig.serializer(), it) }

    /** One page of the activity list, newest first. [cursor] is the cursor of the page after the last one read. */
    override suspend fun listActivities(
        accessToken: String,
        cursor: String?,
        limit: Int,
    ): ApiResult<ActivityPage> =
        get(path = "/v1/activities?limit=$limit" + (cursor?.let { "&cursor=$it" } ?: ""), token = accessToken) {
            json.decodeFromString(ActivityPage.serializer(), it)
        }

    /** One activity of the account. */
    override suspend fun activity(
        accessToken: String,
        id: String,
    ): ApiResult<ActivitySummary> =
        get(
            path = "/v1/activities/$id",
            token = accessToken,
        ) { json.decodeFromString(ActivitySummary.serializer(), it) }

    /** The track of an activity, as GeoJSON. */
    override suspend fun track(
        accessToken: String,
        id: String,
    ): ApiResult<Track> =
        get(path = "/v1/activities/$id/track", token = accessToken) { json.decodeFromString(Track.serializer(), it) }

    /**
     * The analysis of an activity (the splits, the elevation and the series).
     *
     * A `503` is not a failure but a state: the server computes the analysis on the first read, and answers
     * `Retry-After` when it is busy. The app shows "in calcolo" and comes back.
     */
    override suspend fun analysis(
        accessToken: String,
        id: String,
    ): ApiResult<ActivityAnalysis> =
        get(
            path = "/v1/activities/$id/analysis",
            token = accessToken,
            busyOnServiceUnavailable = true,
        ) { json.decodeFromString(ActivityAnalysis.serializer(), it) }

    /** The caller's active consent grants (ADR-0021). Fail-closed: an empty list when the server cannot
     *  be reached or answers unsuccessfully, so the adapter never self-grants RR recording. */
    override suspend fun consents(accessToken: String): ApiResult<List<ConsentGrant>> =
        get(
            path = "/v1/consents",
            token = accessToken,
        ) { body ->
            val element = json.decodeFromString(JsonElement.serializer(), body)
            when (element) {
                is JsonArray ->
                    element.map { item ->
                        val itemStr = item.toString()
                        json.decodeFromString(ConsentGrant.serializer(), itemStr)
                    }
                else -> emptyList()
            }
        }

    /** The profile of the account (TDD-0012). */
    override suspend fun profile(accessToken: String): ApiResult<UserProfile> =
        get(path = "/v1/profile", token = accessToken) { json.decodeFromString(UserProfile.serializer(), it) }

    /** Replaces the profile (TDD-0012): the server stores it whole, so repeating the call is harmless. */
    override suspend fun saveProfile(
        accessToken: String,
        profile: UserProfile,
    ): ApiResult<UserProfile> =
        get(
            path = "/v1/profile",
            token = accessToken,
            method = HttpMethod.Put,
            body = json.encodeToString(UserProfile.serializer(), profile),
        ) { json.decodeFromString(UserProfile.serializer(), it) }

    /**
     * One call, with the repetitions of a temporary failure.
     *
     * @param busyOnServiceUnavailable when true, a `503` is reported as "busy" instead of being repeated: the
     *   analysis endpoint answers `503` while it computes.
     */
    private suspend fun <T> get(
        path: String,
        token: String?,
        busyOnServiceUnavailable: Boolean = false,
        method: HttpMethod = HttpMethod.Get,
        body: String? = null,
        decode: (String) -> T,
    ): ApiResult<T> {
        var attempt = 0
        while (true) {
            attempt++
            val response =
                try {
                    http.request("$baseUrl$path") {
                        this.method = method
                        token?.let { bearerAuth(it) }
                        if (body != null) {
                            contentType(ContentType.Application.Json)
                            setBody(body)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (attempt <= MAX_RETRIES) {
                        sleeper(backoffMs(attempt))
                        continue
                    }
                    return ApiResult(failure = ApiFailure(ApiFailureKind.TRANSIENT))
                }
            val status = response.status.value
            // The body is read in every case: an unread body would keep the connection busy.
            val text = response.bodyAsText()
            if (response.status.isSuccess()) {
                return try {
                    ApiResult(value = decode(text))
                } catch (e: Exception) {
                    ApiResult(failure = ApiFailure(ApiFailureKind.UNEXPECTED, status = status))
                }
            }
            val retryAfter = parseRetryAfterSeconds(response.headers["Retry-After"], nowMs())
            when (status) {
                401 -> return ApiResult(failure = ApiFailure(ApiFailureKind.UNAUTHORIZED, status = status))
                403 -> return ApiResult(failure = ApiFailure(ApiFailureKind.FORBIDDEN, status = status))
                404 -> return ApiResult(failure = ApiFailure(ApiFailureKind.NOT_FOUND, status = status))
                503 -> {
                    if (busyOnServiceUnavailable) {
                        return ApiResult(
                            failure = ApiFailure(ApiFailureKind.BUSY, status = status, retryAfterSeconds = retryAfter),
                        )
                    }
                    if (attempt <= MAX_RETRIES) {
                        sleeper(retryAfterMs(retryAfter, attempt))
                        continue
                    }
                    return ApiResult(
                        failure = ApiFailure(ApiFailureKind.TRANSIENT, status = status, retryAfterSeconds = retryAfter),
                    )
                }
                429 -> {
                    if (attempt <= MAX_RETRIES) {
                        sleeper(retryAfterMs(retryAfter, attempt))
                        continue
                    }
                    return ApiResult(
                        failure = ApiFailure(ApiFailureKind.TRANSIENT, status = status, retryAfterSeconds = retryAfter),
                    )
                }
                in 500..599 -> {
                    if (attempt <= MAX_RETRIES) {
                        sleeper(retryAfterMs(retryAfter, attempt))
                        continue
                    }
                    return ApiResult(
                        failure = ApiFailure(ApiFailureKind.TRANSIENT, status = status, retryAfterSeconds = retryAfter),
                    )
                }
                else -> return ApiResult(failure = ApiFailure(ApiFailureKind.UNEXPECTED, status = status))
            }
        }
    }

    private fun retryAfterMs(
        retryAfterSeconds: Long?,
        attempt: Int,
    ): Long = (retryAfterSeconds?.times(1000) ?: backoffMs(attempt)).coerceAtMost(MAX_RETRY_AFTER_MS)

    private fun backoffMs(attempt: Int): Long = BACKOFF_BASE_MS * (1L shl (attempt - 1))

    companion object {
        /** The page size the client asks for: the same default the server uses. */
        const val DEFAULT_PAGE_SIZE = 50

        /** How many times a temporary failure is repeated before the app is told about it. */
        const val MAX_RETRIES = 2

        private const val BACKOFF_BASE_MS = 500L
        private const val MAX_RETRY_AFTER_MS = 10_000L

        /** The HTTP client the apps use: the engine comes from the classpath (OkHttp / Darwin). */
        fun defaultHttpClient(): HttpClient =
            HttpClient {
                install(HttpTimeout) {
                    connectTimeoutMillis = 15_000
                    requestTimeoutMillis = 60_000
                }
            }

        /** A client for a server, with the default HTTP client. */
        fun create(serverUrl: String): AverynClient = AverynClient(serverUrl)

        private val json =
            Json {
                ignoreUnknownKeys = true
                // The profile is sent whole, defaults included: the server replaces the document.
                encodeDefaults = true
            }
    }
}
