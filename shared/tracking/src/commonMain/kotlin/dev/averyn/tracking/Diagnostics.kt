package dev.averyn.tracking

import dev.averyn.domain.Sport
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Format version stamped on every [DiagnosticsReport]. TDD-0001 F8/§5.4, Q5. */
const val DIAGNOSTICS_FORMAT_VERSION = "diagnostics-v1"

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
)

/** Static context a tester adds alongside [DiagnosticsReport] when filing a device-matrix row. No device IDs. */
@Serializable
data class DeviceInfo(
    val model: String,
    val osVersion: String,
    val appVersion: String,
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
