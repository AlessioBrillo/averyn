package dev.averyn.campaign

import dev.averyn.backend.imports.ImportedHeartRate
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
            "S5" -> s5(input)
            "S6" -> s6(input)
            "S15", "S29" -> s15(input)
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
        Check(
            "no gap over 10 s",
            r.quality.longestGapMs < S2_MAX_GAP_MS,
            "longest ${r.quality.longestGapMs} ms; ${r.locationGaps.count} gap(s) over ${r.locationGaps.thresholdMs} ms while recording",
        ),
    )
}

private fun s5(input: Inputs): List<Check> {
    val r = input.report
    val kill = input.killAtMs
    val before = r.recoveryGaps.lastOrNull()?.lastSampleBeforeMs
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
            "no NON_MONOTONIC_TIME burst (at most $S6_MAX_NON_MONOTONIC)",
            nonMonotonic <= S6_MAX_NON_MONOTONIC,
            "$nonMonotonic flagged",
        ),
    )
}

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
        r.recoveryGaps.lastOrNull()?.let { g ->
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
