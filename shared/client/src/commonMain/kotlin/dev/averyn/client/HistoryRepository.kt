package dev.averyn.client

import dev.averyn.api.ActivitySummary
import dev.averyn.api.UnitSystem
import dev.averyn.api.UserProfile
import dev.averyn.sync.SyncState
import dev.averyn.sync.SyncStatus
import dev.averyn.sync.SyncStatusStore
import dev.averyn.tracking.ActivityStore
import dev.averyn.tracking.LocalActivitySummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path

/** One activity of the local store, as the merge sees it. */
internal data class LocalActivity(
    val activityId: String,
    val sport: String,
    val startedAtMs: Long,
    val summary: LocalActivitySummary?,
    val syncState: HistorySyncState,
)

/**
 * The history of the account, read from the server and from the device (TDD-0011).
 *
 * The server is the authority: a row of the list is an activity of the server, plus the activities that are
 * only on this device (not uploaded yet, or refused). An activity the server deleted disappears from the list
 * and stays in the "device data" section, where the user can delete it (TDD-0011 §8a).
 *
 * Everything is read from the cache first and refreshed afterwards: the screen shows what it has while the
 * network is away, and says that what it shows is a copy.
 */
class HistoryRepository(
    private val store: ActivityStore,
    private val cache: ActivityCache,
    val client: ActivityReader,
    private val statuses: SyncStatusStore = SyncStatusStore(store),
    private val nowMs: () -> Long = { currentTimeMillis() },
) {
    companion object {
        /**
         * The repository the apps use: the cache lives in its own directory (never in the store of the
         * activities), and the client talks to one server.
         *
         * The clock is the one of the platform; the overload with a clock is for the tests.
         */
        fun create(
            store: ActivityStore,
            cacheDirectory: String,
            serverUrl: String,
        ): HistoryRepository = create(store, cacheDirectory, serverUrl) { currentTimeMillis() }

        /** The repository with a given clock (the tests). */
        fun create(
            store: ActivityStore,
            cacheDirectory: String,
            serverUrl: String,
            nowMs: () -> Long,
        ): HistoryRepository {
            val server = serverUrl.trimEnd('/')
            return HistoryRepository(
                store = store,
                cache = ActivityCache(Path(cacheDirectory), server, nowMs = nowMs),
                client = AverynClient.create(server),
                nowMs = nowMs,
            )
        }
    }

    /** The list as it is on the device: what was cached, plus the activities that are only here. */
    suspend fun loadCached(): HistorySnapshot =
        withContext(Dispatchers.Default) {
            val cached = cache.readSummaries()
            snapshot(
                serverItems = cached?.items.orEmpty(),
                nextCursor = cached?.nextCursor,
                fromCache = true,
                cachedAtMs = cached?.savedAtMs,
            )
        }

    /** Reads the list from the server and writes it to the cache. */
    suspend fun refresh(accessToken: String?): HistorySnapshot =
        withContext(Dispatchers.Default) {
            val cached = cache.readSummaries()
            if (accessToken.isNullOrBlank()) {
                return@withContext snapshot(
                    serverItems = cached?.items.orEmpty(),
                    nextCursor = cached?.nextCursor,
                    fromCache = true,
                    cachedAtMs = cached?.savedAtMs,
                    needsLogin = true,
                )
            }
            val result = client.listActivities(accessToken, cursor = null, limit = AverynClient.DEFAULT_PAGE_SIZE)
            val failure = result.failure
            if (failure != null) {
                return@withContext snapshot(
                    serverItems = cached?.items.orEmpty(),
                    nextCursor = cached?.nextCursor,
                    fromCache = true,
                    cachedAtMs = cached?.savedAtMs,
                    needsLogin = failure.kind == ApiFailureKind.UNAUTHORIZED,
                    failure = failure,
                )
            }
            val page = result.value ?: return@withContext snapshot(cached?.items.orEmpty(), cached?.nextCursor, true)
            val merged = mergeCachedSummaries(cached?.items.orEmpty(), page.items)
            val savedAt = nowMs()
            cache.writeSummaries(merged, page.nextCursor, savedAt)
            snapshot(serverItems = merged, nextCursor = page.nextCursor, fromCache = false, cachedAtMs = savedAt)
        }

    /** Reads the next page of the list and keeps it in the cache. */
    suspend fun loadMore(
        accessToken: String?,
        cursor: String,
    ): HistorySnapshot =
        withContext(Dispatchers.Default) {
            val cached = cache.readSummaries()
            if (accessToken.isNullOrBlank()) {
                return@withContext snapshot(
                    serverItems = cached?.items.orEmpty(),
                    nextCursor = cached?.nextCursor,
                    fromCache = true,
                    cachedAtMs = cached?.savedAtMs,
                    needsLogin = true,
                )
            }
            val result = client.listActivities(accessToken, cursor = cursor, limit = AverynClient.DEFAULT_PAGE_SIZE)
            val page = result.value
            if (page == null) {
                return@withContext snapshot(
                    serverItems = cached?.items.orEmpty(),
                    nextCursor = cached?.nextCursor,
                    fromCache = true,
                    cachedAtMs = cached?.savedAtMs,
                    failure = result.failure,
                )
            }
            val merged = mergeCachedSummaries(cached?.items.orEmpty(), page.items)
            val savedAt = nowMs()
            cache.writeSummaries(merged, page.nextCursor, savedAt)
            snapshot(serverItems = merged, nextCursor = page.nextCursor, fromCache = false, cachedAtMs = savedAt)
        }

    /** One activity, as it was cached the last time it was opened. */
    suspend fun cachedDetail(serverId: String): DetailSnapshot? =
        withContext(Dispatchers.Default) {
            cache.readDetail(serverId)?.toDetailSnapshot(fromCache = true)
        }

    /**
     * Opens an activity: its summary and its track.
     *
     * The analysis is a separate call ([refreshAnalysis]): the server computes it on the first read, and the
     * screen must not wait for it to show the numbers of the activity.
     */
    suspend fun refreshDetail(
        serverId: String,
        accessToken: String?,
    ): DetailSnapshot =
        withContext(Dispatchers.Default) {
            val cached = cache.readDetail(serverId)
            if (accessToken.isNullOrBlank()) {
                val failure = ApiFailure(ApiFailureKind.UNAUTHORIZED)
                return@withContext cached?.toDetailSnapshot(fromCache = true, failure = failure)
                    ?: DetailSnapshot(failure = failure)
            }
            val summaryResult = client.activity(accessToken, serverId)
            val summary = summaryResult.value
            if (summary == null) {
                val failure = summaryResult.failure ?: ApiFailure(ApiFailureKind.UNEXPECTED)
                if (failure.kind == ApiFailureKind.NOT_FOUND) {
                    // The server does not have it any more: the copy on the device stays, the cached one goes.
                    cache.deleteDetail(serverId)
                    cache.removeSummary(serverId)
                    return@withContext DetailSnapshot(deletedOnServer = true, failure = failure)
                }
                return@withContext cached?.toDetailSnapshot(fromCache = true, failure = failure)
                    ?: DetailSnapshot(failure = failure)
            }
            val trackResult = client.track(accessToken, serverId)
            val now = nowMs()
            val detail =
                CachedActivityDetail(
                    savedAtMs = now,
                    summary = summary,
                    track = trackResult.value,
                    analysis = cached?.analysis,
                    trackFetchedAtMs = if (trackResult.failure == null) now else cached?.trackFetchedAtMs,
                    analysisFetchedAtMs = cached?.analysisFetchedAtMs,
                )
            cache.writeDetail(detail)
            detail.toDetailSnapshot(fromCache = false)
        }

    /**
     * Reads the analysis of an activity.
     *
     * A busy server (`503` with `Retry-After`) is not a failure but a state: the activity is "in calcolo" and
     * the app comes back when the server says so.
     */
    suspend fun refreshAnalysis(
        serverId: String,
        accessToken: String?,
    ): DetailSnapshot =
        withContext(Dispatchers.Default) {
            val cached =
                cache.readDetail(serverId)
                    ?: return@withContext DetailSnapshot(failure = ApiFailure(ApiFailureKind.NOT_FOUND))
            if (accessToken.isNullOrBlank()) {
                return@withContext cached.toDetailSnapshot(fromCache = true)
            }
            val result = client.analysis(accessToken, serverId)
            val failure = result.failure
            val now = nowMs()
            val updated =
                cached.copy(
                    savedAtMs = now,
                    analysis = result.value ?: cached.analysis,
                    analysisFetchedAtMs =
                        if (failure == null ||
                            failure.kind == ApiFailureKind.NOT_FOUND
                        ) {
                            now
                        } else {
                            cached.analysisFetchedAtMs
                        },
                )
            cache.writeDetail(updated)
            val state =
                when {
                    updated.analysis != null -> AnalysisState.READY
                    failure?.kind == ApiFailureKind.BUSY -> AnalysisState.COMPUTING
                    else -> AnalysisState.UNAVAILABLE
                }
            updated.toDetailSnapshot(fromCache = false, analysisState = state, failure = failure)
        }

    /** The activities stored on this device, with their files and their state. */
    suspend fun deviceData(): List<DeviceActivityItem> =
        withContext(Dispatchers.Default) {
            val serverIds =
                cache
                    .readSummaries()
                    ?.items
                    ?.map { it.clientActivityId }
                    ?.toSet()
                    .orEmpty()
            store.list().sortedByDescending { it.startedAtMs }.map { metadata ->
                val state = statuses.statusOf(metadata.activityId).toHistorySyncState()
                DeviceActivityItem(
                    activityId = metadata.activityId,
                    sport = metadata.sport.name,
                    startedAtMs = metadata.startedAtMs,
                    summary = store.readLocalSummary(metadata.activityId),
                    syncState = state,
                    deletedOnServer =
                        state == HistorySyncState.DELETED_ON_SERVER ||
                            (state == HistorySyncState.SYNCED && metadata.activityId !in serverIds),
                    localBytes = store.localSize(metadata.activityId),
                    hasHeartRateSidecar = store.hasRr(metadata.activityId),
                )
            }
        }

    /**
     * Throws one activity off this device: the raw file, the local summary, the sync status and the
     * heart-rate sidecar go with it. The server keeps what it has.
     */
    suspend fun deleteLocal(activityId: String) =
        withContext(Dispatchers.Default) {
            store.delete(activityId)
            store.deleteRr(activityId)
            statuses.delete(activityId)
            statuses.deleteRr(activityId)
        }

    /** The unit system as the device last knew it: the cached one, or metric. Never touches the network. */
    suspend fun cachedUnits(): UnitSystem =
        withContext(Dispatchers.Default) {
            cache.readUnits() ?: UnitSystem.METRIC
        }

    /**
     * Reads the profile from the server (TDD-0012). The unit system is remembered, so that the numbers are written
     * the same way without a connection; the maximum heart rate is not kept on the device.
     */
    suspend fun refreshProfile(accessToken: String?): ProfileSnapshot =
        withContext(Dispatchers.Default) {
            val cached = cache.readUnits() ?: UnitSystem.METRIC
            if (accessToken.isNullOrBlank()) return@withContext ProfileSnapshot(units = cached, fromCache = true)
            val result = client.profile(accessToken)
            val profile = result.value
            if (profile == null) {
                return@withContext ProfileSnapshot(units = cached, fromCache = true, failure = result.failure)
            }
            cache.writeUnits(profile.units)
            ProfileSnapshot(profile = profile, units = profile.units)
        }

    /** Saves the profile (TDD-0012). Only what the server stored is kept: a refused profile changes nothing here. */
    suspend fun saveProfile(
        accessToken: String,
        profile: UserProfile,
    ): ProfileSnapshot =
        withContext(Dispatchers.Default) {
            val result = client.saveProfile(accessToken, profile)
            val stored = result.value
            if (stored == null) {
                return@withContext ProfileSnapshot(
                    units = cache.readUnits() ?: UnitSystem.METRIC,
                    fromCache = true,
                    failure = result.failure,
                )
            }
            cache.writeUnits(stored.units)
            ProfileSnapshot(profile = stored, units = stored.units)
        }

    /** Throws the copy of the server away (the user asked for it, or the session ended). */
    suspend fun clearCache() =
        withContext(Dispatchers.Default) {
            cache.clear()
        }

    /** What the cache holds, for the "offline data" line of the account screen. */
    suspend fun cacheStats(): CacheStats =
        withContext(Dispatchers.Default) {
            cache.stats()
        }

    private fun snapshot(
        serverItems: List<ActivitySummary>,
        nextCursor: String?,
        fromCache: Boolean,
        cachedAtMs: Long? = null,
        needsLogin: Boolean = false,
        failure: ApiFailure? = null,
    ): HistorySnapshot =
        HistorySnapshot(
            items = mergeHistory(serverItems, localActivities()),
            nextCursor = nextCursor,
            fromCache = fromCache,
            cachedAtMs = cachedAtMs,
            needsLogin = needsLogin,
            failure = failure,
        )

    private fun localActivities(): List<LocalActivity> =
        store.list().map { metadata ->
            val status = statuses.statusOf(metadata.activityId)
            LocalActivity(
                activityId = metadata.activityId,
                sport = metadata.sport.name,
                startedAtMs = metadata.startedAtMs,
                summary = store.readLocalSummary(metadata.activityId),
                syncState = status.toHistorySyncState(),
            )
        }
}

/**
 * The rows of the list: the activities of the server, plus the ones that are only on this device.
 *
 * An activity the server has and the device has too is one row (the numbers of the server). An activity the
 * device has and the server does not (it was deleted there) is not in the list any more: it stays in the
 * "device data" section until the user deletes it.
 */
internal fun mergeHistory(
    serverItems: List<ActivitySummary>,
    localActivities: List<LocalActivity>,
): List<HistoryItem> {
    val serverIds = serverItems.mapTo(HashSet()) { it.clientActivityId }
    val rows = serverItems.map { it.toHistoryItem() }
    val localRows =
        localActivities
            .filter { local ->
                local.activityId !in serverIds &&
                    local.syncState != HistorySyncState.SYNCED &&
                    local.syncState != HistorySyncState.DELETED_ON_SERVER
            }.map { it.toHistoryItem() }
    return (rows + localRows).sortedByDescending { it.startedAtMs }
}

/** The new page first, the older cached ones after it: the list of the server is the freshest truth. */
internal fun mergeCachedSummaries(
    cached: List<ActivitySummary>,
    fresh: List<ActivitySummary>,
): List<ActivitySummary> {
    val freshIds = fresh.mapTo(HashSet()) { it.id }
    return (fresh + cached.filter { it.id !in freshIds })
        .sortedByDescending { isoInstantToEpochMillis(it.startedAt) ?: 0L }
}

/** One cached activity, as the detail screen shows it. */
private fun CachedActivityDetail.toDetailSnapshot(
    fromCache: Boolean,
    analysisState: AnalysisState? = null,
    failure: ApiFailure? = null,
): DetailSnapshot =
    DetailSnapshot(
        serverId = summary.id,
        clientActivityId = summary.clientActivityId,
        sport = summary.sport,
        startedAtMs = isoInstantToEpochMillis(summary.startedAt) ?: 0L,
        summary = summary,
        analysis = analysis,
        analysisState = analysisState ?: if (analysis != null) AnalysisState.READY else AnalysisState.NOT_CACHED,
        fromCache = fromCache,
        cachedAtMs = savedAtMs,
        failure = failure,
    )

private fun ActivitySummary.toHistoryItem(): HistoryItem =
    HistoryItem(
        serverId = id,
        clientActivityId = clientActivityId,
        sport = sport,
        startedAtMs = isoInstantToEpochMillis(startedAt) ?: 0L,
        distanceM = distanceM,
        elapsedMs = elapsedMs,
        movingMs = movingMs,
        pausedMs = pausedMs,
        stationaryMs = stationaryMs,
        averageSpeedMps = averageSpeedMps,
        paceSecPerKm = paceSecPerKm,
        qualityGrade = qualityGrade,
        droppedRecords = droppedRecords,
        avgHrBpm = avgHrBpm,
        maxHrBpm = maxHrBpm,
        source = if (source == "gpx") HistorySource.GPX else HistorySource.DEVICE,
        syncState = null,
        localEstimate = false,
    )

private fun LocalActivity.toHistoryItem(): HistoryItem =
    HistoryItem(
        serverId = null,
        clientActivityId = activityId,
        sport = sport,
        startedAtMs = startedAtMs,
        distanceM = summary?.distanceM ?: 0.0,
        elapsedMs = summary?.elapsedMs ?: 0L,
        movingMs = summary?.movingMs ?: 0L,
        pausedMs = summary?.pausedMs ?: 0L,
        stationaryMs = summary?.stationaryMs ?: 0L,
        averageSpeedMps = summary?.averageSpeedMps ?: 0.0,
        paceSecPerKm = summary?.paceSecPerKm,
        qualityGrade = summary?.qualityGrade.orEmpty(),
        droppedRecords = summary?.droppedRecords ?: 0,
        avgHrBpm = summary?.avgHrBpm ?: 0,
        maxHrBpm = summary?.maxHrBpm ?: 0,
        source = HistorySource.LOCAL,
        syncState = syncState,
        localEstimate = summary != null,
    )

/** The state of an activity on its way to the server, as the list shows it. */
internal fun SyncStatus.toHistorySyncState(): HistorySyncState =
    when (state) {
        SyncState.LOCAL_ONLY -> HistorySyncState.LOCAL_ONLY
        SyncState.QUEUED -> HistorySyncState.QUEUED
        SyncState.UPLOADING -> HistorySyncState.UPLOADING
        SyncState.READY -> HistorySyncState.SYNCED
        SyncState.FAILED_RETRYABLE -> HistorySyncState.FAILED_RETRYABLE
        SyncState.FAILED_PERMANENT ->
            if (lastError ==
                DELETED_ON_SERVER
            ) {
                HistorySyncState.DELETED_ON_SERVER
            } else {
                HistorySyncState.FAILED_PERMANENT
            }
    }

/** The error the sync writes when the server deleted the activity (TDD-0002). */
private const val DELETED_ON_SERVER = "DELETED_ON_SERVER"
