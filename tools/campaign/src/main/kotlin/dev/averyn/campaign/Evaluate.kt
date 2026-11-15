package dev.averyn.campaign

import dev.averyn.backend.imports.ImportedHeartRate
import dev.averyn.domain.ActivityState
import dev.averyn.domain.Reason
import dev.averyn.tracking.DeviceInfo
import dev.averyn.tracking.DiagnosticsReport
import dev.averyn.tracking.QualityFlag
import dev.averyn.tracking.QualityGrade
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.math.abs

/** PASS and FAIL are decided by numbers; MANUAL is "nothing failed, but a criterion needs a person or a file". */
enum class Verdict { PASS, FAIL, MANUAL }

/** One criterion of a row. [ok] is null when the input it needs was not given. */
class Check(
    val name: String,
    val ok: Boolean?,
    val detail: String,
)

class RowResult(
    val row: String,
    val checks: List<Check>,
) {
    val verdict: Verdict =
        when {
            checks.any { it.ok == false } -> Verdict.FAIL
            checks.isEmpty() || checks.any { it.ok == null } -> Verdict.MANUAL
            else -> Verdict.PASS
        }
}

/** The strap's own numbers over the window of the activity. */
class HrReference(
    val avgBpm: Int,
    val maxBpm: Int,
    val samples: Int,
)

/** What the evaluator may know besides the report. Everything but the report is optional. */
class Inputs(
    val report: DiagnosticsReport,
    val device: DeviceInfo,
    val hrReference: HrReference? = null,
    val gpxValid: Boolean? = null,
    /** Wall-clock instant of the kill (S5), noted by the tester. */
    val killAtMs: Long? = null,
    val referenceDistanceM: Double? = null,
    /** The sum of the pauses the tester made (S3), in ms. */
    val expectedPausedMs: Long? = null,
)

// The thresholds are the "Pass when" column of docs/testing/device-matrix.md. Where that text says "no burst", the number
// here is the evaluator's, and the name of the check says so.
private const val S1_DISTANCE_TOLERANCE = 0.02
private const val S2_MIN_ELAPSED_MS = 3_600_000L
private const val S2_MAX_GAP_MS = 10_000L
private const val S5_MAX_LOSS_MS = 1_000L
private const val S6_MAX_NON_MONOTONIC = 3
private const val S15_MIN_ELAPSED_MS = 600_000L
private const val S15_MAX_DELTA_BPM = 5
private const val S15_MAX_DROPOUT_MS = 30_000L
private const val S3_PAUSED_TOLERANCE_MS = 2_000L
private const val S11_MIN_ELAPSED_MS = 3L * 3_600_000L
private const val S19_MIN_COVERAGE_PERCENT = 95.0

fun parseExport(json: String): Pair<DiagnosticsReport, DeviceInfo> {
    val lenient = Json { ignoreUnknownKeys = true }
    val root = lenient.parseToJsonElement(json).jsonObject
    val report = lenient.decodeFromJsonElement(DiagnosticsReport.serializer(), root.getValue("report"))
    val device = lenient.decodeFromJsonElement(DeviceInfo.serializer(), root.getValue("device"))
    return report to device
}

/** The strap's average and maximum over [fromMs, toMs]; null when it has no reading in the window. */
fun hrReference(
    samples: List<ImportedHeartRate>,
    fromMs: Long,
    toMs: Long,
): HrReference? {
    val inWindow = samples.filter { it.timeMs in fromMs..toMs && it.bpm > 0 }
    if (inWindow.isEmpty()) return null
    return HrReference(Math.round(inWindow.map { it.bpm }.average()).toInt(), inWindow.maxOf { it.bpm }, inWindow.size)
}

fun evaluate(
    row: String,
    input: Inputs,
): RowResult {
    val checks =
        when (row) {
            "S1" -> s1(input)
            "S2" -> s2(input)
            "S3" -> s3(input)
            "S4" -> s4(input)
            "S5" -> s5(input)
            "S6" -> s6(input)
            "S7" -> s7(input)
            "S8" -> s8(input)
            "S10" -> s10(input)
            "S11" -> s11(input)
            "S15" -> s15(input)
            "S19" -> s19(input)
            // The band as a strap: the numbers are those of the strap, the rest of the row is read by a person.
            "S29" -> s15(input) + s29ByHand()
            else -> listOf(Check("no machine check for this row", null, "read the Pass-when column by hand"))
        }
    return RowResult(row, checks)
}

private fun s1(input: Inputs): List<Check> {
    val r = input.report
    val reference = input.referenceDistanceM
    val distanceDetail =
        if (reference == null) {
            "give --ref-distance-m (recorded ${r.distanceM.toInt()} m)"
        } else {
            "${r.distanceM.toInt()} m vs ${reference.toInt()} m"
        }
    return listOf(
        Check(
            "grade is FAIR or better",
            r.quality.grade in setOf(QualityGrade.GOOD, QualityGrade.FAIR),
            r.quality.grade.name,
        ),
        Check("GPX validates", input.gpxValid, if (input.gpxValid == null) "give --gpx" else input.gpxValid.toString()),
        Check(
            "distance within 2% of the reference",
            reference?.let { abs(r.distanceM - it) <= S1_DISTANCE_TOLERANCE * it },
            distanceDetail,
        ),
    )
}

private fun s2(input: Inputs): List<Check> {
    val r = input.report
    return listOf(
        Check("at least 1 h", r.elapsedMs >= S2_MIN_ELAPSED_MS, "${r.elapsedMs / 60_000} min"),
        // The matrix excludes known signal loss (a tunnel): the tool cannot know it, so a long gap is for a person.
        Check(
            "no gap over 10 s outside known signal loss",
            if (r.quality.longestGapMs < S2_MAX_GAP_MS) true else null,
            "longest ${r.quality.longestGapMs} ms; ${r.locationGaps.count} gap(s) over " +
                "${r.locationGaps.thresholdMs} ms while recording" +
                if (r.quality.longestGapMs >= S2_MAX_GAP_MS) ": was it a known signal loss?" else "",
        ),
    )
}

private fun s5(input: Inputs): List<Check> {
    val r = input.report
    val kill = input.killAtMs
    val before = r.recoveryGaps.firstOrNull()?.lastSampleBeforeMs
    val lossMs = if (kill == null || before == null) null else kill - before
    return listOf(
        Check("recovered", r.recoveries >= 1, "${r.recoveries} recovery(ies)"),
        Check(
            "last stored sample within 1 s of the kill",
            lossMs?.let { it <= S5_MAX_LOSS_MS },
            if (kill == null) "give --kill-at" else "kill minus last stored sample = ${lossMs ?: "-"} ms",
        ),
    )
}

private fun s6(input: Inputs): List<Check> {
    val r = input.report
    val nonMonotonic = r.quality.flagCounts[QualityFlag.NON_MONOTONIC_TIME] ?: 0
    val reboots = r.recoveryGaps.count { it.rebootDetected }
    return listOf(
        Check("recovered", r.recoveries >= 1, "${r.recoveries} recovery(ies)"),
        Check("reboot detected", reboots > 0, "rebootDetected in $reboots gap(s)"),
        Check(
            "samples were recorded after the reboot",
            r.recoveryGaps.any { it.rebootDetected && it.firstSampleAfterMs != null },
            "first sample after: ${r.recoveryGaps.firstOrNull { it.rebootDetected }?.firstSampleAfterMs ?: "-"}",
        ),
        Check(
            "no NON_MONOTONIC_TIME burst (at most $S6_MAX_NON_MONOTONIC)",
            nonMonotonic <= S6_MAX_NON_MONOTONIC,
            "$nonMonotonic flagged",
        ),
    )
}

private fun s3(input: Inputs): List<Check> {
    val r = input.report
    val expected = input.expectedPausedMs
    return listOf(
        Check("paused time was recorded", r.pausedMs > 0, "${r.pausedMs} ms"),
        Check(
            "pausedMs equals the sum of the pauses within 2 s",
            expected?.let { abs(r.pausedMs - it) <= S3_PAUSED_TOLERANCE_MS },
            if (expected ==
                null
            ) {
                "give --paused-s (the sum you timed; recorded ${r.pausedMs / 1000} s)"
            } else {
                "${r.pausedMs} ms vs $expected ms"
            },
        ),
        Check("no samples counted while paused", null, "read by hand: one GPX segment per recording interval"),
    )
}

private fun s4(input: Inputs): List<Check> {
    val r = input.report
    val poor = r.quality.flagCounts[QualityFlag.POOR_ACCURACY] ?: 0
    return listOf(
        Check(
            "the bad signal is counted (POOR_ACCURACY or a gap)",
            poor + r.locationGaps.count > 0,
            "$poor POOR_ACCURACY flag(s), ${r.locationGaps.count} gap(s), longest ${r.locationGaps.longestMs} ms",
        ),
        Check(
            "the track continues after it",
            r.recordingSamples > 0 && r.elapsedMs > 0,
            "${r.recordingSamples} recording sample(s)",
        ),
    )
}

private fun s7(input: Inputs): List<Check> {
    val paused =
        input.report.events.filter {
            it.state == ActivityState.PAUSED &&
                (it.reason == Reason.PERMISSION_REVOKED || it.reason == Reason.PERMISSION_DOWNGRADED)
        }
    return listOf(
        Check(
            "paused with a PERMISSION_* reason (iOS), never silently stopped",
            paused.isNotEmpty(),
            if (paused.isEmpty()) {
                "no such pause in the timeline (Android: recovered on the next launch, as in S5)"
            } else {
                paused.joinToString { it.reason.name }
            },
        ),
        Check("banner shown; resuming without precise location is refused", null, "read on the phone"),
    )
}

private fun s8(input: Inputs): List<Check> {
    val r = input.report
    return listOf(
        Check(
            "battery drain per hour is in the report",
            r.batteryDrainPercentPerHour != null,
            "${r.batteryDrainPercentPerHour ?: "-"} %/h",
        ),
        Check(
            "battery saver state is recorded",
            input.device.powerSaverOn != null,
            "powerSaverOn=${input.device.powerSaverOn ?: "-"}",
        ),
        Check("recording continued or failed visibly", null, "read on the phone: no silent stop"),
    )
}

private fun s10(input: Inputs): List<Check> {
    val r = input.report
    val lost = r.recordLosses.values.sum()
    return listOf(
        Check("recovered after the update", r.recoveries >= 1, "${r.recoveries} recovery(ies)"),
        Check("no stored data lost", lost == 0, "$lost record(s) lost"),
    )
}

private fun s11(input: Inputs): List<Check> {
    val r = input.report
    return listOf(
        Check("at least 3 h", r.elapsedMs >= S11_MIN_ELAPSED_MS, "${r.elapsedMs / 60_000} min"),
        Check("GPX validates", input.gpxValid, if (input.gpxValid == null) "give --gpx" else input.gpxValid.toString()),
        Check(
            "battery drain per hour is in the report",
            r.batteryDrainPercentPerHour != null,
            "${r.batteryDrainPercentPerHour ?: "-"} %/h",
        ),
        Check("memory sane", null, "read on the phone"),
    )
}

private fun s19(input: Inputs): List<Check> {
    val hr = input.report.heartRate
    return listOf(
        Check("at least 30 min", input.report.elapsedMs >= 30 * 60_000L, "${input.report.elapsedMs / 60_000} min"),
        Check("heart rate present", hr != null, if (hr == null) "no heart-rate sample was recorded" else ""),
        Check(
            "coverage of the recording time is at least 95 %",
            hr?.let { it.coveragePercent >= S19_MIN_COVERAGE_PERCENT },
            "${hr?.coveragePercent?.toInt() ?: "-"} %",
        ),
        Check("the chart breaks where the strap was silent", null, "read on the web"),
    )
}

/** What S29 asks beyond the numbers of S15, none of which is in the report. */
private fun s29ByHand(): List<Check> =
    listOf(
        Check("the band is listed by its name", null, "read on the phone"),
        Check("no crash if it disconnects and comes back", null, "unplug the band mid-run and read"),
        Check("no Synapse-specific permission is asked", null, "read the permission prompts"),
    )

private fun s15(input: Inputs): List<Check> {
    val r = input.report
    val hr = r.heartRate
    val ref = input.hrReference

    fun within(
        mine: Int?,
        theirs: Int?,
    ): Boolean? = if (mine == null || theirs == null) null else abs(mine - theirs) <= S15_MAX_DELTA_BPM

    fun detail(
        mine: Int?,
        theirs: Int?,
    ) = if (theirs == null) "give --ref (the strap's FIT file)" else "${mine ?: "-"} vs $theirs"
    return listOf(
        Check("at least 10 min", r.elapsedMs >= S15_MIN_ELAPSED_MS, "${r.elapsedMs / 60_000} min"),
        Check(
            "heart rate present",
            hr != null,
            if (hr ==
                null
            ) {
                "no heart-rate sample was recorded"
            } else {
                "coverage ${hr.coveragePercent.toInt()}%"
            },
        ),
        Check("average within 5 bpm", within(hr?.avgBpm, ref?.avgBpm), detail(hr?.avgBpm, ref?.avgBpm)),
        Check("maximum within 5 bpm", within(hr?.maxBpm, ref?.maxBpm), detail(hr?.maxBpm, ref?.maxBpm)),
        Check(
            "no dropout over 30 s",
            hr?.let {
                it.gaps.longestMs <= S15_MAX_DROPOUT_MS
            },
            "longest ${hr?.gaps?.longestMs ?: "-"} ms",
        ),
    )
}

/** The row to paste into the Results table of docs/testing/device-matrix.md, then the checks, one per line. */
fun render(
    result: RowResult,
    input: Inputs,
    date: String,
): String {
    val r = input.report
    val d = input.device
    val lost = r.recordLosses.values.sum() + r.droppedInputs.values.sum()
    val recovery =
        r.recoveryGaps.firstOrNull()?.let { g ->
            val loss = input.killAtMs?.let { k -> g.lastSampleBeforeMs?.let { k - it } }
            "gap ${g.gapMs ?: "-"} ms" + (loss?.let { ", kill to last sample $it ms" } ?: "")
        } ?: "-"
    val battery = r.batteryDrainPercentPerHour?.let { "%.1f".format(it) } ?: "-"
    val gpx = input.gpxValid?.let { if (it) "yes" else "NO" } ?: "-"
    val failed = result.checks.filter { it.ok == false }.joinToString("; ") { it.name }
    val row =
        "| $date | ${d.model} | ${d.osVersion} | ${d.appVersion} / ${d.buildId ?: "-"} | ${result.row} | " +
            "${r.elapsedMs / 60_000} min | $battery | $lost | $recovery | $gpx | ${r.quality.grade} | " +
            "${result.verdict} | $failed |"
    val lines =
        result.checks.joinToString("\n") { c ->
            val mark =
                when (c.ok) {
                    true -> "ok  "
                    false -> "FAIL"
                    null -> "?   "
                }
            "  [$mark] ${c.name}: ${c.detail}"
        }
    return "$row\n$lines"
}
