package dev.averyn.client

import dev.averyn.analysis.hidingZones
import dev.averyn.api.ActivitySummary
import dev.averyn.api.PrivacyZone
import dev.averyn.api.PrivacyZones
import dev.averyn.api.UnitSystem
import dev.averyn.api.UserProfile
import dev.averyn.sync.SyncState
import dev.averyn.sync.SyncStatus
import dev.averyn.sync.SyncStatusStore
import dev.averyn.tracking.ActivityStore
import dev.averyn.tracking.LocalActivitySummary
import dev.averyn.tracking.SynapseSidecarKind
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
    private val localProfile: LocalProfileStore? = null,
    private val localZones: LocalZonesStore? = null,
    private val nowMs: () -> Long = { currentTimeMillis() },
) {
    companion object {
        /** The most pages [refreshAll] reads (50 activities each): 10,000 activities, then it says there is more. */
        const val MAX_PAGES = 200

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
            profileDirectory: String,
        ): HistoryRepository = create(store, cacheDirectory, serverUrl, profileDirectory) { currentTimeMillis() }

        /** The repository with a given clock (the tests). */
        fun create(
            store: ActivityStore,
            cacheDirectory: String,
            serverUrl: String,
            profileDirectory: String,
            nowMs: () -> Long,
        ): HistoryRepository {
            val server = serverUrl.trimEnd('/')
            return HistoryRepository(
                store = store,
                cache = ActivityCache(Path(cacheDirectory), server, nowMs = nowMs),
                client = AverynClient.create(server),
                localProfile = LocalProfileStore(profileDirectory),
                localZones = LocalZonesStore(profileDirectory),
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

    /**
     * Reads every page of the list and keeps the whole history in the cache: what the progress totals and the calendar
     * count (S45), which the first page alone would understate. When the walk reaches the end the cache is exactly what
     * the server has: an activity deleted there leaves the copy. When it stops half way (a failure, or [MAX_PAGES]) the
     * pages read are merged into what was cached and the cursor says there is more.
     */
    suspend fun refreshAll(accessToken: String?): HistorySnapshot =
        withContext(Dispatchers.Default) {
            val cached = cache.readSummaries()
            if (accessToken.isNullOrBlank()) return@withContext refresh(accessToken)
            val read = mutableListOf<ActivitySummary>()
            var cursor: String? = null
            var pages = 0
            do {
                val result = client.listActivities(accessToken, cursor, AverynClient.DEFAULT_PAGE_SIZE)
                val page = result.value
                if (page == null) {
                    val failure = result.failure
                    return@withContext snapshot(
                        serverItems = mergeCachedSummaries(cached?.items.orEmpty(), read),
                        nextCursor = cached?.nextCursor,
                        fromCache = true,
                        cachedAtMs = cached?.savedAtMs,
                        needsLogin = failure?.kind == ApiFailureKind.UNAUTHORIZED,
                        failure = failure,
                    )
                }
                read += page.items
                cursor = page.nextCursor
                pages++
            } while (cursor != null && pages < MAX_PAGES)
            val items = if (cursor == null) read else mergeCachedSummaries(cached?.items.orEmpty(), read)
            val savedAt = nowMs()
            cache.writeSummaries(items, cursor, savedAt)
            snapshot(serverItems = items, nextCursor = cursor, fromCache = false, cachedAtMs = savedAt)
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
            cache.readDetail(serverId)?.toDetailSnapshot(zones = shownZones(), fromCache = true)
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
                return@withContext cached?.toDetailSnapshot(zones = shownZones(), fromCache = true, failure = failure)
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
                return@withContext cached?.toDetailSnapshot(zones = shownZones(), fromCache = true, failure = failure)
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
            detail.toDetailSnapshot(zones = shownZones(), fromCache = false)
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
                return@withContext cached.toDetailSnapshot(zones = shownZones(), fromCache = true)
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
            updated.toDetailSnapshot(zones = shownZones(), fromCache = false, analysisState = state, failure = failure)
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
     * heart-rate and band sidecars go with it. The server keeps what it has.
     */
    suspend fun deleteLocal(
        activityId: String,
        inProgressId: String? = null,
    ): Boolean =
        withContext(Dispatchers.Default) {
            // The recorder keeps appending to an activity that runs: deleting its file would leave a file with no header.
            if (activityId == inProgressId) return@withContext false
            store.delete(activityId)
            store.deleteRr(activityId)
            statuses.delete(activityId)
            statuses.deleteRr(activityId)
            for (kind in SynapseSidecarKind.entries) {
                store.deleteSynapse(activityId, kind)
                statuses.deleteSynapse(activityId, kind)
            }
            true
        }

    /** The unit system as the device last knew it: the cached one, or metric. Never touches the network. */
    suspend fun cachedUnits(): UnitSystem =
        withContext(Dispatchers.Default) {
            localProfile
                ?.takeIf { it.exists() }
                ?.read()
                ?.profile
                ?.units ?: cache.readUnits() ?: UnitSystem.METRIC
        }

    /** The zones this phone hides, read from the file each time: a change made a moment ago counts at once. */
    private fun shownZones(): List<PrivacyZone> =
        localZones
            ?.read()
            ?.zones
            ?.zones
            .orEmpty()

    /** The privacy zones as this phone has them, with no account and no network. */
    suspend fun privacyZones(): PrivacyZones = withContext(Dispatchers.Default) { PrivacyZones(shownZones()) }

    /**
     * Saves the zones on the phone first, so they work with no account (and cover the very next map), then sends them
     * when [accessToken] is there. Zones that are not valid ([PrivacyZones.problem]) change nothing. If the server
     * cannot be reached they stay marked as unsynced and go up with the next [syncPrivacyZones].
     */
    suspend fun savePrivacyZones(
        accessToken: String?,
        zones: PrivacyZones,
    ): ZonesSnapshot =
        withContext(Dispatchers.Default) {
            if (zones.problem() != null) {
                return@withContext ZonesSnapshot(
                    PrivacyZones(shownZones()),
                    fromCache = true,
                    failure = ApiFailure(ApiFailureKind.UNEXPECTED, status = 422),
                )
            }
            if (localZones?.write(StoredZones(zones, unsynced = true)) == false) {
                return@withContext ZonesSnapshot(
                    PrivacyZones(shownZones()),
                    fromCache = true,
                    failure = ApiFailure(ApiFailureKind.UNEXPECTED),
                )
            }
            if (accessToken.isNullOrBlank()) return@withContext ZonesSnapshot(zones, fromCache = true)
            syncZonesNow(accessToken)
        }

    /**
     * Brings the phone and the server together: zones changed here and not sent yet go up, otherwise the server's
     * are taken. Without a token it only reads the phone's. The last change made wins (the same rule, and the same
     * limit, as [syncProfile]).
     */
    suspend fun syncPrivacyZones(accessToken: String?): ZonesSnapshot =
        withContext(Dispatchers.Default) {
            if (accessToken.isNullOrBlank()) {
                return@withContext ZonesSnapshot(
                    PrivacyZones(shownZones()),
                    fromCache = true,
                )
            }
            syncZonesNow(accessToken)
        }

    private suspend fun syncZonesNow(accessToken: String): ZonesSnapshot {
        val local = localZones?.read() ?: StoredZones()
        val result =
            if (local.unsynced) {
                client.savePrivacyZones(accessToken, local.zones)
            } else {
                client.privacyZones(accessToken)
            }
        val server = result.value ?: return ZonesSnapshot(local.zones, fromCache = true, failure = result.failure)
        // Edited while the call was on its way: the answer is older than that edit, so it must not replace it.
        val now = localZones?.read()
        if (now != null && now != local) return ZonesSnapshot(now.zones, fromCache = true)
        localZones?.write(StoredZones(server))
        return ZonesSnapshot(server)
    }

    /** The profile as this phone has it, with no account and no network: units and maximum heart rate. */
    suspend fun localProfile(): UserProfile =
        withContext(Dispatchers.Default) { localProfile?.read()?.profile ?: UserProfile() }

    /**
     * Saves the profile on the phone first, so it works with no account, then sends it when [accessToken] is there.
     * A profile that is not valid ([UserProfile.problem]) is refused here and changes nothing.
     * If the server cannot be reached the profile stays marked as unsynced and goes up with the next [syncProfile];
     * the answer then has the local [ProfileSnapshot.profile] and the failure.
     */
    suspend fun saveProfileLocalFirst(
        accessToken: String?,
        profile: UserProfile,
    ): ProfileSnapshot =
        withContext(Dispatchers.Default) {
            if (profile.problem() != null) {
                return@withContext ProfileSnapshot(
                    units = localProfile?.read()?.profile?.units ?: UnitSystem.METRIC,
                    fromCache = true,
                    failure = ApiFailure(ApiFailureKind.UNEXPECTED, status = 422),
                )
            }
            if (localProfile?.write(StoredProfile(profile, unsynced = true)) == false) {
                return@withContext ProfileSnapshot(
                    units = localProfile?.read()?.profile?.units ?: UnitSystem.METRIC,
                    fromCache = true,
                    failure = ApiFailure(ApiFailureKind.UNEXPECTED),
                )
            }
            if (accessToken.isNullOrBlank()) {
                return@withContext ProfileSnapshot(
                    profile,
                    profile.units,
                    fromCache = true,
                )
            }
            syncProfileNow(accessToken)
        }

    /**
     * Brings the phone and the server together: a profile changed here and not sent yet goes up, otherwise the
     * server's one is taken. Without a token it only reads the phone's. The last change made wins.
     *
     * ponytail: "last change wins" is the unsynced flag, not a timestamp. An edit on two devices before either syncs
     * keeps whichever syncs last. A server `updatedAt` would fix it; add it if that ever happens.
     */
    suspend fun syncProfile(accessToken: String?): ProfileSnapshot =
        withContext(Dispatchers.Default) {
            if (accessToken.isNullOrBlank()) {
                val profile =
                    localProfile?.takeIf { it.exists() }?.read()?.profile
                        ?: UserProfile(cache.readUnits() ?: UnitSystem.METRIC, null)
                return@withContext ProfileSnapshot(profile, profile.units, fromCache = true)
            }
            syncProfileNow(accessToken)
        }

    private suspend fun syncProfileNow(accessToken: String): ProfileSnapshot {
        val local = localProfile?.read() ?: StoredProfile()
        val result =
            if (local.unsynced) {
                client.saveProfile(accessToken, local.profile)
            } else {
                client.profile(accessToken)
            }
        val server = result.value
        if (server == null) {
            return ProfileSnapshot(local.profile, local.profile.units, fromCache = true, failure = result.failure)
        }
        // The user can edit while the call is in flight: the answer is older than that edit, so it must not replace it.
        // The edit stays marked as unsynced and goes up with the next sync.
        val now = localProfile?.read()
        if (now != null && now != local) {
            return ProfileSnapshot(now.profile, now.profile.units, fromCache = true)
        }
        localProfile?.write(StoredProfile(server))
        cache.writeUnits(server.units)
        return ProfileSnapshot(server, server.units)
    }

    /**
     * Deletes the account on the server. When it is gone the copy of the server on this device goes too; the
     * recordings on the device are the user's own files and stay. The caller signs out afterwards.
     */
    suspend fun deleteAccount(accessToken: String): ApiResult<Unit> =
        withContext(Dispatchers.Default) {
            val result = client.deleteAccount(accessToken)
            if (result.failure == null) cache.clear()
            result
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
        store.list().filterNot { store.isEmptyFailure(it.activityId) }.map { metadata ->
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
    zones: List<PrivacyZone>,
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
        // The zones of this phone are applied again to a track the server already cut: a zone made offline, or after
        // the copy was taken, must not show a point of it (TDD-0016).
        track = trackMapOf(track?.hidingZones(zones)),
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
