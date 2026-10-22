package dev.averyn.client

import dev.averyn.analysis.ActivityAnalysis
import dev.averyn.api.ActivitySummary
import dev.averyn.api.Track
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The version of the files this build writes. A cache of another version is thrown away: it is a copy of
 * something the server still has.
 */
const val CACHE_SCHEMA_VERSION = 1

/** How many details the cache keeps. */
const val DEFAULT_MAX_CACHED_DETAILS = 10

/** How much the cache may occupy on the disk. */
const val DEFAULT_MAX_CACHE_BYTES = 50L * 1024 * 1024

/** How many summaries of the list the cache keeps. */
const val DEFAULT_MAX_CACHED_SUMMARIES = 500

/** The summaries of the list the client has seen, with the cursor of the next page. */
@Serializable
data class CachedSummaries(
    val schemaVersion: Int = CACHE_SCHEMA_VERSION,
    /** When the list was read from the server. */
    val savedAtMs: Long = 0,
    /** The cursor of the page after the last one that was read; null when the end was reached. */
    val nextCursor: String? = null,
    val items: List<ActivitySummary> = emptyList(),
)

/** One activity the client has opened: its summary, its track and its analysis. */
@Serializable
data class CachedActivityDetail(
    val schemaVersion: Int = CACHE_SCHEMA_VERSION,
    val savedAtMs: Long = 0,
    val summary: ActivitySummary,
    /** The GeoJSON track; null when the activity has none. */
    val track: Track? = null,
    /** The analysis; null when it was not read (or does not exist). */
    val analysis: ActivityAnalysis? = null,
    /** When the track was read; null when it was not read yet. */
    val trackFetchedAtMs: Long? = null,
    /** When the analysis was read; null when it was not read yet. */
    val analysisFetchedAtMs: Long? = null,
)

/** What the cache holds, for the "offline data" line of the account screen. */
data class CacheStats(
    val detailCount: Int = 0,
    val bytes: Long = 0,
    val lastSavedAtMs: Long? = null,
)

@Serializable
private data class CacheManifest(
    val schemaVersion: Int = CACHE_SCHEMA_VERSION,
    val serverUrl: String = "",
)

@Serializable
private data class CacheIndexEntry(
    val lastAccessMs: Long = 0,
    val bytes: Long = 0,
)

@Serializable
private data class CacheIndex(
    val schemaVersion: Int = CACHE_SCHEMA_VERSION,
    val entries: Map<String, CacheIndexEntry> = emptyMap(),
)

/**
 * The copy of the server the device keeps, so that the history can be read without a connection (TDD-0011).
 *
 * What it holds: the summaries of the list, the details of the activities the user opened, and nothing else.
 * No token, no raw file, no heart-rate intervals: the cache is a copy of what the server already has, and it
 * is thrown away when the session ends or the server changes.
 *
 * The server stays the authority: the cache is best-effort, it may be older than the server, and every write
 * is atomic (a temporary file and a rename), so a kill leaves the previous file, never half a JSON.
 */
class ActivityCache(
    private val directory: Path,
    private val serverUrl: String,
    private val maxDetails: Int = DEFAULT_MAX_CACHED_DETAILS,
    private val maxBytes: Long = DEFAULT_MAX_CACHE_BYTES,
    private val maxSummaries: Int = DEFAULT_MAX_CACHED_SUMMARIES,
    private val nowMs: () -> Long = { currentTimeMillis() },
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    /** The summaries of the list, or null when there is nothing cached (or the cache is of another server). */
    fun readSummaries(): CachedSummaries? {
        if (!isUsable()) return null
        val path = Path(directory, SUMMARIES_FILE)
        if (!SystemFileSystem.exists(path)) return null
        return readJson<CachedSummaries>(path)
    }

    /** Writes the summaries of the list, newest first, and the cursor of the next page. */
    fun writeSummaries(
        items: List<ActivitySummary>,
        nextCursor: String?,
        savedAtMs: Long,
    ) {
        if (!isUsable()) return
        val kept = items.sortedByDescending { isoInstantToEpochMillis(it.startedAt) ?: 0L }.take(maxSummaries)
        writeJson(
            Path(directory, SUMMARIES_FILE),
            CachedSummaries(savedAtMs = savedAtMs, nextCursor = nextCursor, items = kept),
        )
    }

    /** Removes one activity from the cached list (the server deleted it). */
    fun removeSummary(id: String) {
        val summaries = readSummaries() ?: return
        if (summaries.items.none { it.id == id }) return
        writeSummaries(summaries.items.filter { it.id != id }, summaries.nextCursor, summaries.savedAtMs)
    }

    /** One cached activity, or null when it was never opened (or the cache is of another server). */
    fun readDetail(id: String): CachedActivityDetail? {
        if (!isUsable()) return null
        if (!isSafeId(id)) return null
        val path = detailPath(id)
        if (!SystemFileSystem.exists(path)) return null
        val detail = readJson<CachedActivityDetail>(path) ?: return null
        touch(id)
        return detail
    }

    /** Writes one activity of the cache and makes room for the others if it is needed. */
    fun writeDetail(detail: CachedActivityDetail) {
        if (!isUsable()) return
        val id = detail.summary.id
        if (!isSafeId(id)) return
        writeJson(detailPath(id), detail)
        val index = readIndex()
        val entry = CacheIndexEntry(lastAccessMs = detail.savedAtMs, bytes = sizeOf(detailPath(id)))
        writeIndex(index.copy(entries = index.entries + (id to entry)))
        prune(index.copy(entries = index.entries + (id to entry)))
    }

    /** Throws one activity out of the cache (the server does not have it any more). */
    fun deleteDetail(id: String) {
        if (!isSafeId(id)) return
        val path = detailPath(id)
        if (SystemFileSystem.exists(path)) SystemFileSystem.delete(path)
        val index = readIndex()
        if (index.entries.containsKey(id)) writeIndex(index.copy(entries = index.entries - id))
    }

    /** Throws the whole cache away: the session ended, or the server changed. */
    fun clear() {
        deleteRecursively(directory)
    }

    /** What the cache holds, for the account screen. */
    fun stats(): CacheStats {
        if (!isUsable()) return CacheStats()
        val index = readIndex()
        val summaries = readSummaries()
        return CacheStats(
            detailCount = index.entries.size,
            bytes = index.entries.values.sumOf { it.bytes },
            lastSavedAtMs = summaries?.savedAtMs,
        )
    }

    /**
     * The cache belongs to one server and one version: anything else is dropped.
     *
     * @return false when the cache cannot be used at all (the caller then reads nothing from it).
     */
    private fun isUsable(): Boolean {
        if (!SystemFileSystem.exists(directory)) SystemFileSystem.createDirectories(directory)
        val manifestPath = Path(directory, MANIFEST_FILE)
        val manifest = readJson<CacheManifest>(manifestPath)
        if (manifest == null) {
            writeJson(manifestPath, CacheManifest(serverUrl = serverUrl))
            return true
        }
        if (manifest.schemaVersion != CACHE_SCHEMA_VERSION || manifest.serverUrl != serverUrl) {
            clear()
            SystemFileSystem.createDirectories(directory)
            writeJson(manifestPath, CacheManifest(serverUrl = serverUrl))
            return true
        }
        return true
    }

    private fun detailPath(id: String) = Path(Path(directory, DETAILS_DIR), "$id.json")

    private fun indexPath() = Path(Path(directory, DETAILS_DIR), INDEX_FILE)

    private fun readIndex(): CacheIndex {
        val index = readJson<CacheIndex>(indexPath())
        if (index != null && index.schemaVersion == CACHE_SCHEMA_VERSION) return index
        return rebuildIndex()
    }

    private fun writeIndex(index: CacheIndex) {
        writeJson(indexPath(), index)
    }

    /** The index is a convenience: when it is missing or broken, it is rebuilt from the files themselves. */
    private fun rebuildIndex(): CacheIndex {
        val entries = mutableMapOf<String, CacheIndexEntry>()
        val detailsDirectory = Path(directory, DETAILS_DIR)
        if (SystemFileSystem.exists(detailsDirectory)) {
            for (file in SystemFileSystem.list(detailsDirectory)) {
                val name = file.name
                if (!name.endsWith(".json") || name == INDEX_FILE) continue
                val id = name.removeSuffix(".json")
                val detail = readJson<CachedActivityDetail>(file) ?: continue
                entries[id] = CacheIndexEntry(lastAccessMs = detail.savedAtMs, bytes = sizeOf(file))
            }
        }
        return CacheIndex(entries = entries)
    }

    /** Notes that an activity was opened, so that it is the last one thrown out. */
    private fun touch(id: String) {
        val index = readIndex()
        val entry = index.entries[id] ?: return
        val touched = entry.copy(lastAccessMs = maxOf(entry.lastAccessMs, nowMs()))
        writeIndex(index.copy(entries = index.entries + (id to touched)))
    }

    /** Makes room: the least recently opened details go out first. */
    private fun prune(index: CacheIndex) {
        var total = index.entries.values.sumOf { it.bytes }
        val count = index.entries.size
        if (count <= maxDetails && total <= maxBytes) return
        val byAge = index.entries.entries.sortedBy { it.value.lastAccessMs }
        var kept = index.entries
        for ((id, entry) in byAge) {
            if (kept.size <= maxDetails && total <= maxBytes) break
            val path = detailPath(id)
            if (SystemFileSystem.exists(path)) SystemFileSystem.delete(path)
            kept = kept - id
            total -= entry.bytes
        }
        writeIndex(index.copy(entries = kept))
    }

    private fun sizeOf(path: Path): Long = SystemFileSystem.metadataOrNull(path)?.size ?: 0L

    private inline fun <reified T> readJson(path: Path): T? {
        if (!SystemFileSystem.exists(path)) return null
        return try {
            json.decodeFromString<T>(SystemFileSystem.source(path).buffered().use { it.readString() })
        } catch (e: SerializationException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    private inline fun <reified T> writeJson(
        path: Path,
        value: T,
    ) {
        try {
            val parent = path.parent ?: return
            if (!SystemFileSystem.exists(parent)) SystemFileSystem.createDirectories(parent)
            val temp = Path(parent, path.name + ".tmp")
            SystemFileSystem.sink(temp, append = false).buffered().use {
                it.writeString(json.encodeToString(value))
            }
            SystemFileSystem.atomicMove(temp, path)
        } catch (e: Exception) {
            // A cache that cannot be written is a cache that is missing: the app keeps working.
        }
    }

    /** Deletes a directory and everything in it (kotlinx-io has no recursive delete of its own). */
    private fun deleteRecursively(path: Path) {
        if (!SystemFileSystem.exists(path)) return
        val children = runCatching { SystemFileSystem.list(path) }.getOrDefault(emptyList())
        if (children.isNotEmpty()) children.forEach { deleteRecursively(it) }
        SystemFileSystem.delete(path)
    }

    private companion object {
        const val MANIFEST_FILE = "manifest.json"
        const val SUMMARIES_FILE = "summaries.json"
        const val DETAILS_DIR = "details"
        const val INDEX_FILE = "index.json"

        /** The ids of the server are UUIDs: nothing else may become a file name. */
        fun isSafeId(id: String): Boolean =
            id.isNotEmpty() && id.length <= 64 && id.all { it.isLetterOrDigit() || it == '-' }
    }
}
