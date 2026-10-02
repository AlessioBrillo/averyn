package dev.averyn.sync

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Where an activity is on its way to the server (TDD-0002 §5.3). */
enum class SyncState {
    /** Not finished yet: nothing to upload. */
    LOCAL_ONLY,

    /** Finished and waiting for an upload attempt. */
    QUEUED,
    UPLOADING,
    READY,

    /** Network, server or authentication trouble: trying again later can work. */
    FAILED_RETRYABLE,

    /** The server rejected the file (409 / 413 / 422 / other 4xx): retrying the same bytes cannot work. */
    FAILED_PERMANENT,
}

@Serializable
data class SyncStatus(
    val state: SyncState,
    val attempts: Int = 0,
    /** Last failure: `AUTH`, `NETWORK` or `HTTP_<code>`; null if there was none. */
    val lastError: String? = null,
    /** The server's id for the activity, once uploaded. */
    val serverActivityId: String? = null,
)

/**
 * Persists one [SyncStatus] per activity as `<activityId>.sync.json` next to the activity's `.jsonl`; the
 * ADR-0015 store itself is never touched. Written to a temp file and renamed, so a kill leaves the old status.
 */
class SyncStatusStore(
    private val directory: Path,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun pathFor(activityId: String) = Path(directory, "$activityId.sync.json")

    /** Null if there is none, or it is unreadable: the caller then simply re-uploads, which is idempotent. */
    fun read(activityId: String): SyncStatus? {
        val path = pathFor(activityId)
        if (!SystemFileSystem.exists(path)) return null
        return try {
            json.decodeFromString(
                SyncStatus.serializer(),
                SystemFileSystem.source(path).buffered().use { it.readString() },
            )
        } catch (e: SerializationException) {
            null
        }
    }

    fun write(
        activityId: String,
        status: SyncStatus,
    ) {
        val target = pathFor(activityId)
        val temp = Path(directory, "$activityId.sync.json.tmp")
        SystemFileSystem.sink(temp, append = false).buffered().use {
            it.writeString(json.encodeToString(SyncStatus.serializer(), status))
        }
        SystemFileSystem.atomicMove(temp, target)
    }
}
