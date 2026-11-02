package dev.averyn.client

import kotlin.math.floor

/*
 * Progress (S45): the totals of the history by week and by month, and the days of a calendar. Computed on the
 * phone from the merged history (the device's own activities and the server's), so it works with no account and
 * needs no endpoint.
 *
 * The boundaries of a week or a month are local: the caller says what the offset of the time zone of the user is
 * at an instant (`TimeZone.getOffset` on Android, `secondsFromGMT(for:)` on iOS), so a run at 23:30 on Sunday
 * counts in that week, and daylight saving needs no rule here.
 */

/** A calendar day in the time zone of the user. Ordered by date. */
data class CivilDate(
    val year: Int,
    val month: Int,
    val day: Int,
) : Comparable<CivilDate> {
    override fun compareTo(other: CivilDate): Int =
        compareValuesBy(this, other, CivilDate::year, CivilDate::month, CivilDate::day)
}

enum class ProgressPeriod { WEEK, MONTH }

/** What was done in one week (starting on Monday, ISO) or one month. */
data class PeriodTotals(
    /** The Monday of the week, or the first day of the month. */
    val start: CivilDate,
    val activities: Int,
    val distanceM: Double,
    val movingMs: Long,
    val elapsedMs: Long,
)

/**
 * The totals per [period], the most recent first; a period without an activity is not in the list. [sport] keeps
 * the activities of one sport (null: all of them).
 */
fun progressTotals(
    items: List<HistoryItem>,
    period: ProgressPeriod,
    offsetMsAt: (Long) -> Long,
    sport: String? = null,
): List<PeriodTotals> =
    items
        .filter { sport == null || it.sport == sport }
        .groupBy { periodStart(localDay(it.startedAtMs, offsetMsAt), period) }
        .map { (start, group) ->
            PeriodTotals(
                start = start,
                activities = group.size,
                distanceM = group.sumOf { it.distanceM },
                movingMs = group.sumOf { it.movingMs },
                elapsedMs = group.sumOf { it.elapsedMs },
            )
        }.sortedByDescending { it.start }

/** The days with at least one activity, and how many each had: the marks of a calendar. */
fun activityDays(
    items: List<HistoryItem>,
    offsetMsAt: (Long) -> Long,
): Map<CivilDate, Int> = items.groupingBy { localDay(it.startedAtMs, offsetMsAt) }.eachCount()

private const val DAY_MS = 86_400_000L

private fun floorDiv(
    a: Long,
    b: Long,
): Long = floor(a.toDouble() / b.toDouble()).toLong()

private fun floorMod(
    a: Long,
    b: Long,
): Long = a - floorDiv(a, b) * b

private fun localDay(
    epochMs: Long,
    offsetMsAt: (Long) -> Long,
): CivilDate = civilFromDays(floorDiv(epochMs + offsetMsAt(epochMs), DAY_MS))

private fun periodStart(
    day: CivilDate,
    period: ProgressPeriod,
): CivilDate =
    when (period) {
        ProgressPeriod.MONTH -> CivilDate(day.year, day.month, 1)
        ProgressPeriod.WEEK -> {
            val days = daysSinceEpoch(day)
            // 1970-01-01 was a Thursday: with Monday as 0 it is 3.
            civilFromDays(days - floorMod(days + 3, 7L))
        }
    }

// The two functions below are the well-known days-from-civil algorithm and its inverse (as in Time.kt: a
// ponytail, no datetime library for the few functions the client needs).
private fun daysSinceEpoch(day: CivilDate): Long {
    val y = (if (day.month <= 2) day.year - 1 else day.year).toLong()
    val era = floorDiv(y, 400L)
    val yoe = y - era * 400
    val doy = (153 * (day.month + (if (day.month > 2) -3 else 9)) + 2) / 5 + day.day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097 + doe - 719_468
}

private fun civilFromDays(daysSinceEpoch: Long): CivilDate {
    val z = daysSinceEpoch + 719_468
    val era = floorDiv(z, 146_097L)
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = (doy - (153 * mp + 2) / 5 + 1).toInt()
    val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
    val year = (yoe + era * 400 + (if (month <= 2) 1 else 0)).toInt()
    return CivilDate(year, month, day)
}
