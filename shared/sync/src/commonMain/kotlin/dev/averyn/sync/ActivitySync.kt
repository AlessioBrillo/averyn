package dev.averyn.sync

import dev.averyn.domain.ActivityState
import dev.averyn.tracking.ActivityStore
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class SyncRunResult(
    val uploaded: Int,
    /** Activities left for a later run (network, server or auth trouble). */
    val retryable: Int,
    /** Activities the server rejected for good. */
    val permanent: Int,
    /** There was no access token, or the server refused it: sign in again, then run again. */
    val needsLogin: Boolean,
)

/**
 * Uploads finished activities to the server (TDD-0002): one idempotent `PUT /v1/activities/{id}` of the raw file
 * each, oldest first, and remembers the outcome in a [SyncStatusStore]. Retry timing and backoff belong to the
 * platform scheduler (WorkManager / BGTaskScheduler); this just does one pass when called.
 */
class ActivitySync internal constructor(
    private val store: ActivityStore,
    private val statuses: SyncStatusStore,
    private val http: HttpClient,
    serverUrl: String,
) {
    private val baseUrl = serverUrl.trimEnd('/')

    companion object {
        /** For the apps (and Swift): the HTTP engine is the platform's (OkHttp / Darwin). */
        fun create(
            store: ActivityStore,
            serverUrl: String,
        ): ActivitySync =
            ActivitySync(
                store,
                SyncStatusStore(store.directory),
                HttpClient {
                    install(HttpTimeout) {
                        connectTimeoutMillis = 15_000
                        requestTimeoutMillis = 120_000
                    }
                },
                serverUrl,
            )
    }

    /** What to show for [activityId]: the stored status, with unfinished and interrupted uploads made explicit. */
    fun statusOf(activityId: String): SyncStatus {
        val stored = statuses.read(activityId)
        if (stored != null && stored.state != SyncState.UPLOADING) return stored
        // UPLOADING on disk with no upload running means the app died mid-upload: it is queued again.
        val state = if (isFinished(activityId)) SyncState.QUEUED else SyncState.LOCAL_ONLY
        return SyncStatus(state, attempts = stored?.attempts ?: 0, lastError = stored?.lastError)
    }

    private fun isFinished(activityId: String): Boolean {
        val last = store.lastStateOf(activityId)
        return last == ActivityState.COMPLETED || last == ActivityState.FAILED
    }

    suspend fun syncPending(accessToken: String?): SyncRunResult {
        var uploaded = 0
        var retryable = 0
        var permanent = 0
        var needsLogin = false

        for (metadata in store.list().sortedBy { it.startedAtMs }) {
            val id = metadata.activityId
            val previous = statuses.read(id)
            if (previous?.state == SyncState.READY || previous?.state == SyncState.FAILED_PERMANENT) continue
            if (!isFinished(id)) continue
            if (accessToken == null) {
                needsLogin = true
                break
            }

            val attempts = (previous?.attempts ?: 0) + 1
            statuses.write(id, SyncStatus(SyncState.UPLOADING, attempts, previous?.lastError))
            val response = upload(id, accessToken)
            val code = response?.status?.value
            val status =
                when (code) {
                    200, 201 ->
                        SyncStatus(
                            SyncState.READY,
                            attempts,
                            serverActivityId = serverIdOf(checkNotNull(response)),
                        )
                    401 -> SyncStatus(SyncState.FAILED_RETRYABLE, attempts, "AUTH")
                    null -> SyncStatus(SyncState.FAILED_RETRYABLE, attempts, "NETWORK")
                    408, 429, in 500..599 -> SyncStatus(SyncState.FAILED_RETRYABLE, attempts, "HTTP_$code")
                    else -> SyncStatus(SyncState.FAILED_PERMANENT, attempts, "HTTP_$code")
                }
            statuses.write(id, status)

            when (status.state) {
                SyncState.READY -> uploaded++
                SyncState.FAILED_PERMANENT -> permanent++
                else -> retryable++
            }
            if (status.lastError == "AUTH") {
                needsLogin = true
                break // the same token would be refused for every other activity too
            }
        }
        return SyncRunResult(uploaded, retryable, permanent, needsLogin)
    }

    /** The response, or null if the request could not be completed (no network, timeout, ...). */
    private suspend fun upload(
        activityId: String,
        accessToken: String,
    ): HttpResponse? =
        try {
            http.put("$baseUrl/v1/activities/$activityId") {
                bearerAuth(accessToken)
                contentType(ContentType("application", "x-ndjson"))
                setBody(store.readRaw(activityId))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    private suspend fun serverIdOf(response: HttpResponse): String? =
        try {
            Json
                .parseToJsonElement(response.bodyAsText())
                .jsonObject["id"]
                ?.jsonPrimitive
                ?.content
        } catch (e: Exception) {
            null // the activity is on the server regardless; the id is only a convenience
        }
}
