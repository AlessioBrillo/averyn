package dev.averyn.client

import dev.averyn.analysis.ActivityAnalysis
import dev.averyn.api.ActivitySummary
import dev.averyn.api.UnitSystem
import dev.averyn.api.UserProfile
import dev.averyn.tracking.LocalActivitySummary

/** Where an activity of the list comes from. */
enum class HistorySource {
    /** Recorded on a device. */
    DEVICE,

    /** Imported from a file (TDD-0008). */
    GPX,

    /** Only on this device: not on the server (yet). */
    LOCAL,
}

/** Where the activity is on its way to the server. */
enum class HistorySyncState {
    /** Not finished yet: nothing to upload. */
    LOCAL_ONLY,

    /** Finished and waiting for an upload attempt. */
    QUEUED,

    UPLOADING,

    /** The server has it. */
    SYNCED,

    /** Network, server or authentication trouble: trying again later can work. */
    FAILED_RETRYABLE,

    /** The server refused the file: retrying the same bytes cannot work. */
    FAILED_PERMANENT,

    /** The server deleted it (or refused it as already deleted): the local copy is all there is. */
    DELETED_ON_SERVER,
}

/** Whether the analysis of an activity can be shown. */
enum class AnalysisState {
    /** The analysis is there. */
    READY,

    /** The server is computing it: it says when to come back. */
    COMPUTING,

    /** The analysis was not cached and was not read yet. */
    NOT_CACHED,

    /** The activity is not on the server yet: the analysis comes with the upload. */
    NOT_SYNCED,

    /** There is no analysis for this activity. */
    UNAVAILABLE,
}

/**
 * One row of the history list (TDD-0011).
 *
 * A row is either an activity of the server or an activity that is only on this device. The numbers of a local
 * row come from the local summary sidecar and are marked as an estimate: the server's numbers win as soon as
 * the activity is on the server.
 */
data class HistoryItem(
    /** The id of the activity on the server; null when the activity is only on this device. */
    val serverId: String? = null,
    /** The id the activity has on the device that recorded it: the key that joins the two sides. */
    val clientActivityId: String,
    val sport: String,
    val startedAtMs: Long,
    val distanceM: Double = 0.0,
    val elapsedMs: Long = 0,
    val movingMs: Long = 0,
    val pausedMs: Long = 0,
    val stationaryMs: Long = 0,
    val averageSpeedMps: Double = 0.0,
    val paceSecPerKm: Double? = null,
    val qualityGrade: String = "",
    val droppedRecords: Int = 0,
    val avgHrBpm: Int = 0,
    val maxHrBpm: Int = 0,
    val source: HistorySource = HistorySource.DEVICE,
    /** Null for an activity that is only on the server. */
    val syncState: HistorySyncState? = null,
    /** True when the numbers come from the local sidecar and not from the server. */
    val localEstimate: Boolean = false,
)

/** The list of the activities, as the screen shows it. */
data class HistorySnapshot(
    val items: List<HistoryItem> = emptyList(),
    /** The cursor of the next page; null when the list is complete (or was never read from the server). */
    val nextCursor: String? = null,
    /** True when the server could not be reached and the list comes from the cache. */
    val fromCache: Boolean = false,
    /** When the cached list was read from the server. */
    val cachedAtMs: Long? = null,
    /** True when the app has to ask the user to sign in again. */
    val needsLogin: Boolean = false,
    /** Why the server could not be reached, when it could not. */
    val failure: ApiFailure? = null,
)

/** One activity, opened: its summary and its analysis. */
data class DetailSnapshot(
    val serverId: String? = null,
    val clientActivityId: String = "",
    val sport: String = "",
    val startedAtMs: Long = 0,
    val summary: ActivitySummary? = null,
    /** The local estimate of the numbers, when the activity is not on the server (yet). */
    val localSummary: LocalActivitySummary? = null,
    val analysis: ActivityAnalysis? = null,
    val analysisState: AnalysisState = AnalysisState.UNAVAILABLE,
    /** True when what is shown comes from the cache and not from the server. */
    val fromCache: Boolean = false,
    /** When the cached copy was read from the server. */
    val cachedAtMs: Long? = null,
    /** True when the server does not have the activity any more. */
    val deletedOnServer: Boolean = false,
    val failure: ApiFailure? = null,
)

/** One activity stored on this device, with its files (TDD-0011 "dati sul dispositivo"). */
data class DeviceActivityItem(
    val activityId: String,
    val sport: String,
    val startedAtMs: Long,
    val summary: LocalActivitySummary? = null,
    val syncState: HistorySyncState = HistorySyncState.LOCAL_ONLY,
    /** True when the server does not have it any more: the file stays here until the user deletes it. */
    val deletedOnServer: Boolean = false,
    /** The size of the local files, in bytes. */
    val localBytes: Long = 0,
    /** True when the heart-rate sidecar is next to it (ADR-0020). */
    val hasHeartRateSidecar: Boolean = false,
)

/**
 * The profile as the screen needs it (TDD-0012): the settings of the account when the server answered, and the unit
 * system either way (the last one the server said, or metric if it never did).
 */
data class ProfileSnapshot(
    /** The profile the server answered; null when it could not be read. */
    val profile: UserProfile? = null,
    /** The unit system to write numbers in. */
    val units: UnitSystem = UnitSystem.METRIC,
    /** True when [units] comes from the cache and not from the server. */
    val fromCache: Boolean = false,
    val failure: ApiFailure? = null,
)
