package dev.averyn.tracking

import dev.averyn.domain.ActivityState
import dev.averyn.domain.Reason
import dev.averyn.domain.Sport
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Format version stamped on every [DiagnosticsReport]. TDD-0001 F8/§5.4, Q5; v2 adds the TDD-0006 §5.5 fields. */
const val DIAGNOSTICS_FORMAT_VERSION = "diagnostics-v2"

/**
 * Aggregate quality/battery report for one completed activity (TDD-0001 F8), built by [ActivityRecorder]
 * from counters it already keeps while live-recording or replaying during [ActivityRecorder.recover] — no
 * second pass over raw samples. Carries no coordinates or device identifiers (docs/privacy/data-classification.md).
 */
@Serializable
data class DiagnosticsReport(
    val formatVersion: String,
    val activityId: String,
    val sport: Sport,
    val startedAtMs: Long,
    val totalSamples: Int,
    val recordingSamples: Int,
    val recordLosses: Map<RecordLossReason, Int>,
    /** How many times this activity was recovered after a kill/crash/reboot (F4), across all app runs. */
    val recoveries: Int,
    val distanceM: Double,
    val elapsedMs: Long,
    val movingMs: Long,
    val pausedMs: Long,
    val quality: QualityReport,
    val batteryStartPercent: Int?,
    val batteryEndPercent: Int?,
    /**
     * (start − end) / elapsed hours. Null without at least two battery readings. Only meaningful for a run
     * with no charging and no [recoveries] (an app restart resets what "start" means for this number) — the
     * tester judges that from the fields above, same device-matrix row.
     */
    val batteryDrainPercentPerHour: Double?,
    /** `distance-v1`, `time-v1`, ...: which algorithms produced the numbers above (a matrix row must name them). */
    val algorithms: AlgorithmVersions,
    val stationaryMs: Long,
    /** Wall-clock time of the last thing that happened to the activity, or null before any event. */
    val endedAtMs: Long?,
    /** Inputs the recorder could not use, by reason (F5). Store-line losses are in [recordLosses]. */
    val droppedInputs: Map<DroppedInputReason, Int>,
    /** One entry per recovery, in order: what was lost to the interruption (TDD-0006 §5.5). */
    val recoveryGaps: List<RecoveryGap>,
    /** The state timeline with reasons, on the continuous (effective) clock. */
    val events: List<TimelineEvent>,
)

@Serializable
data class AlgorithmVersions(
    val distance: String,
    val time: String,
    val speed: String,
    val quality: String,
)

/** Why the recorder did not use an input it was handed (F5: counted, never silent). */
enum class DroppedInputReason {
    /** A fix arrived while no activity existed (e.g. a service restarted before recovery). */
    NO_ACTIVE_ACTIVITY,

    /** A late fix arrived after the activity had ended. */
    ACTIVITY_ENDED,

    /** The platform produced a fix the sample model rejects (NaN, out-of-range coordinate). */
    INVALID_SAMPLE,
}

/**
 * What one interruption cost. All times are wall clock (display only): [lastSampleBeforeMs] is the last
 * stored fix before the recovery, [firstSampleAfterMs] the first one after it. A tester compares
 * [lastSampleBeforeMs] with the instant they killed the app to check the "<= 1 sample interval" claim.
 */
@Serializable
data class RecoveryGap(
    val lastSampleBeforeMs: Long?,
    val recoveredAtMs: Long,
    val firstSampleAfterMs: Long?,
    val gapMs: Long?,
    /** The monotonic clock was found restarted (reboot) and the offset changed. */
    val rebootDetected: Boolean,
)

@Serializable
data class TimelineEvent(
    /** Effective (continuous) monotonic millis; see `StateEvent.clockOffsetMs`. */
    val elapsedRealtimeMs: Long,
    val timeMs: Long,
    val state: ActivityState,
    val reason: Reason,
)

/**
 * Collects [RecoveryGap]s while an activity is replayed or recorded. One class used by both `replay()` and
 * `ActivityRecorder`, so the live report and a rebuilt one cannot disagree.
 */
internal class RecoveryGapTracker {
    private val gaps = mutableListOf<RecoveryGap>()
    private var lastSampleTimeMs: Long? = null
    private var awaitingFirstSample = false

    fun onSample(timeMs: Long) {
        if (awaitingFirstSample) {
            val open = gaps.last()
            val before = open.lastSampleBeforeMs
            gaps[gaps.lastIndex] =
                open.copy(firstSampleAfterMs = timeMs, gapMs = if (before == null) null else timeMs - before)
            awaitingFirstSample = false
        }
        lastSampleTimeMs = timeMs
    }

    fun onRecovered(
        recoveredAtMs: Long,
        rebootDetected: Boolean,
    ) {
        gaps += RecoveryGap(lastSampleTimeMs, recoveredAtMs, null, null, rebootDetected)
        awaitingFirstSample = true
    }

    fun snapshot(): List<RecoveryGap> = gaps.toList()
}

/** Static context a tester adds alongside [DiagnosticsReport] when filing a device-matrix row. No device IDs. */
@Serializable
data class DeviceInfo(
    /** Hardware model: Build.MODEL on Android, the `utsname` machine identifier (e.g. iPhone15,2) on iOS. */
    val model: String,
    val osVersion: String,
    val appVersion: String,
    // Everything below is nullable and has no Kotlin default on purpose: Swift cannot see default arguments,
    // so a native caller states each value (or nil) explicitly and a new field cannot be forgotten.
    val manufacturer: String?,
    /** The commit the build came from (ADR-0019), so a matrix row and a store crash report can be matched. */
    val buildId: String?,
    /** e.g. `PRECISE_ALWAYS`, `APPROXIMATE`, `WHEN_IN_USE`, `DENIED`; platform-specific vocabulary, never free text. */
    val locationPermission: String?,
    val powerSaverOn: Boolean?,
    /** Android only: the app is exempt from battery optimisation. */
    val batteryOptimisationExempt: Boolean?,
)

private val diagnosticsJsonFormat = Json { prettyPrint = true }

/**
 * Pretty JSON for export/sharing — what the Android/iOS "export diagnostics" actions write out. An
 * extension function (like [exportGpxToFile]) so it's a plain member call from Swift, not a `DiagnosticsKt`
 * file-facade call.
 */
fun DiagnosticsReport.toJson(device: DeviceInfo): String =
    diagnosticsJsonFormat.encodeToString(
        DiagnosticsExport.serializer(),
        DiagnosticsExport(this, device),
    )

@Serializable
private data class DiagnosticsExport(
    val report: DiagnosticsReport,
    val device: DeviceInfo,
)
