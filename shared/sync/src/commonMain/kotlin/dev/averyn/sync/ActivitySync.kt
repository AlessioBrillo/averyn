package dev.averyn.sync

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

// The failures a later pass can still fix; the codes a SyncStatus carries (see syncPending).
private val RETRYABLE_HTTP = setOf("HTTP_408", "HTTP_429") + (500..599).map { "HTTP_$it" }

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
                SyncStatusStore(store),
                HttpClient {
                    install(HttpTimeout) {
                        connectTimeoutMillis = 15_000
                        requestTimeoutMillis = 120_000
                    }
                },
                serverUrl,
            )
    }

    /** Releases the HTTP client; the apps create an [ActivitySync] per run (the server URL can change between runs). */
    fun close() = http.close()

    suspend fun syncPending(accessToken: String?): SyncRunResult {
        var uploaded = 0
        var retryable = 0
        var permanent = 0
        var needsLogin = false

        for (metadata in store.list().sortedBy { it.startedAtMs }) {
            val id = metadata.activityId
            val previous = statuses.read(id)
            if (previous?.state == SyncState.READY) {
                // The activity is up; its heart-rate sidecar may not be (it failed retryably, or it came into being
                // after the upload): it is tried again here, otherwise it would never go up.
                if (!sidecarPending(id)) continue
                if (accessToken == null) {
                    needsLogin = true
                    break
                }
                when (uploadRrSidecar(id, accessToken)?.lastError) {
                    "AUTH" -> {
                        needsLogin = true
                        break
                    }
                    "NETWORK", in RETRYABLE_HTTP -> retryable++
                }
                continue
            }
            if (previous?.state == SyncState.FAILED_PERMANENT) continue
            if (!statuses.isFinished(id)) continue
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
                    // Distinct, permanent reasons the apps can explain (TDD-0006 R11): not "HTTP_403" / "HTTP_410".
                    403 -> SyncStatus(SyncState.FAILED_PERMANENT, attempts, "ACCOUNT_DELETED")
                    410 -> SyncStatus(SyncState.FAILED_PERMANENT, attempts, "DELETED_ON_SERVER")
                    408, 429, in 500..599 -> SyncStatus(SyncState.FAILED_RETRYABLE, attempts, "HTTP_$code")
                    else -> SyncStatus(SyncState.FAILED_PERMANENT, attempts, "HTTP_$code")
                }
            statuses.write(id, status)

            when (status.state) {
                SyncState.READY -> {
                    uploaded++
                    // The heart-rate sidecar goes up after the activity it belongs to, and never holds it back.
                    when (uploadRrSidecar(id, accessToken)?.lastError) {
                        "AUTH" -> needsLogin = true
                        "NETWORK", in RETRYABLE_HTTP -> retryable++
                    }
                }
                SyncState.FAILED_PERMANENT -> permanent++
                else -> retryable++
            }
            if (needsLogin || status.lastError == "AUTH") {
                needsLogin = true
                break // the same token would be refused for every other activity too
            }
            // A server that asked to slow down (or has no upload slot) is not asked again for every other activity:
            // the platform scheduler's backoff retries the pass. A deleted account refuses every upload alike.
            if (code == 429 || code == 503 || status.lastError == "ACCOUNT_DELETED") break
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

    /**
     * Uploads the heart-rate sidecar of an activity, when it has one (ADR-0020/0021).
     *
     * The sidecar is a separate object with a separate consent: a refusal is permanent (the consent was
     * withdrawn or the data was deleted on the server) and the local copy goes with it, because the user
     * withdrew the consent for exactly that data.
     */
    private suspend fun uploadRrSidecar(
        activityId: String,
        accessToken: String,
    ): SyncStatus? {
        if (!sidecarPending(activityId)) return null
        val previous = statuses.readRr(activityId)
        val attempts = (previous?.attempts ?: 0) + 1
        statuses.writeRr(activityId, SyncStatus(SyncState.UPLOADING, attempts, previous?.lastError))
        val response = uploadRrFile(activityId, accessToken)
        val code = response?.status?.value
        val status =
            when (code) {
                200, 201 -> SyncStatus(SyncState.READY, attempts)
                401 -> SyncStatus(SyncState.FAILED_RETRYABLE, attempts, "AUTH")
                null -> SyncStatus(SyncState.FAILED_RETRYABLE, attempts, "NETWORK")
                403 -> SyncStatus(SyncState.FAILED_PERMANENT, attempts, "CONSENT_REVOKED")
                410 -> SyncStatus(SyncState.FAILED_PERMANENT, attempts, "DELETED_ON_SERVER")
                408, 429, in 500..599 -> SyncStatus(SyncState.FAILED_RETRYABLE, attempts, "HTTP_$code")
                else -> SyncStatus(SyncState.FAILED_PERMANENT, attempts, "HTTP_$code")
            }
        statuses.writeRr(activityId, status)
        // The consent is gone or the server deleted the data: the local copy goes too.
        if (status.lastError == "CONSENT_REVOKED" || status.lastError == "DELETED_ON_SERVER") {
            store.deleteRr(activityId)
        }
        return status
    }

    /** Whether the activity has a sidecar that is neither on the server nor refused for good. */
    private fun sidecarPending(activityId: String): Boolean {
        if (store.rrMetadataOf(activityId) == null) return false
        val state = statuses.readRr(activityId)?.state
        return state != SyncState.READY && state != SyncState.FAILED_PERMANENT
    }

    /** The response, or null if the request could not be completed (no network, timeout, ...). */
    private suspend fun uploadRrFile(
        activityId: String,
        accessToken: String,
    ): HttpResponse? =
        try {
            http.put("$baseUrl/v1/activities/$activityId/rr") {
                bearerAuth(accessToken)
                contentType(ContentType("application", "x-ndjson"))
                setBody(store.readRrRaw(activityId))
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
