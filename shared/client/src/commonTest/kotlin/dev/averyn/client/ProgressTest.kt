package dev.averyn.client

import kotlin.test.Test
import kotlin.test.assertEquals

class ProgressTest {
    private val utc: (Long) -> Long = { 0L }
    private val hour = 3_600_000L

    private fun item(
        iso: String,
        distanceM: Double = 5_000.0,
        sport: String = "RUN",
    ) = HistoryItem(
        clientActivityId = iso,
        sport = sport,
        startedAtMs = isoInstantToEpochMillis(iso)!!,
        distanceM = distanceM,
        movingMs = 1_500_000,
        elapsedMs = 1_800_000,
    )

    // 2026-10-26 is a Monday; 2026-10-31 a Saturday, 2026-11-01 a Sunday, 2026-11-02 the next Monday.

    @Test
    fun aWeekStartsOnMondayAndTheSundayRunBelongsToIt() {
        val items = listOf(item("2026-10-26T06:00:00Z"), item("2026-11-01T23:30:00Z"), item("2026-11-02T00:10:00Z"))

        val weeks = progressTotals(items, ProgressPeriod.WEEK, utc)

        assertEquals(listOf(CivilDate(2026, 11, 2), CivilDate(2026, 10, 26)), weeks.map { it.start })
        assertEquals(listOf(1, 2), weeks.map { it.activities })
        assertEquals(10_000.0, weeks[1].distanceM)
        assertEquals(3_000_000L, weeks[1].movingMs)
    }

    @Test
    fun theLocalOffsetDecidesWhichDayAnActivityIsOn() {
        val late = listOf(item("2026-10-31T23:30:00Z"))

        // In UTC it is Saturday night; two hours east of it, it is already Sunday 01:30.
        assertEquals(setOf(CivilDate(2026, 10, 31)), activityDays(late, utc).keys)
        assertEquals(setOf(CivilDate(2026, 11, 1)), activityDays(late) { 2 * hour }.keys)
        assertEquals(setOf(CivilDate(2026, 10, 31)), activityDays(late) { -2 * hour }.keys)
    }

    @Test
    fun aMonthIsTheCalendarMonthAndEmptyOnesAreAbsent() {
        val items = listOf(item("2026-10-31T10:00:00Z"), item("2026-11-01T10:00:00Z"), item("2026-12-15T10:00:00Z"))

        val months = progressTotals(items, ProgressPeriod.MONTH, utc)

        assertEquals(
            listOf(CivilDate(2026, 12, 1), CivilDate(2026, 11, 1), CivilDate(2026, 10, 1)),
            months.map { it.start },
        )
    }

    @Test
    fun aWeekCanStartInThePreviousMonthAndYear() {
        // 2027-01-01 is a Friday: its week starts on Monday 2026-12-28.
        val weeks = progressTotals(listOf(item("2027-01-01T10:00:00Z")), ProgressPeriod.WEEK, utc)

        assertEquals(CivilDate(2026, 12, 28), weeks.single().start)
    }

    @Test
    fun theSportFilterKeepsOneSport() {
        val items = listOf(item("2026-10-26T06:00:00Z"), item("2026-10-27T06:00:00Z", sport = "RIDE"))

        assertEquals(1, progressTotals(items, ProgressPeriod.WEEK, utc, sport = "RIDE").single().activities)
        assertEquals(2, progressTotals(items, ProgressPeriod.WEEK, utc).single().activities)
    }

    @Test
    fun leapDaysAndDatesBeforeTheEpochWork() {
        // 2024-02-29 is a Thursday; the week starts on Monday 2024-02-26.
        assertEquals(
            CivilDate(2024, 2, 26),
            progressTotals(listOf(item("2024-02-29T12:00:00Z")), ProgressPeriod.WEEK, utc).single().start,
        )
        // 1969-12-31 is a Wednesday; the week starts on Monday 1969-12-29.
        assertEquals(
            CivilDate(1969, 12, 29),
            progressTotals(listOf(item("1969-12-31T12:00:00Z")), ProgressPeriod.WEEK, utc).single().start,
        )
    }
}
