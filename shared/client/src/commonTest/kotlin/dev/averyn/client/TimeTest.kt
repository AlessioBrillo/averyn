package dev.averyn.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The instants of the contract and the `Retry-After` of the server (TDD-0011). */
class TimeTest {
    @Test
    fun `an ISO instant becomes epoch millis`() {
        assertEquals(0L, isoInstantToEpochMillis("1970-01-01T00:00:00Z"))
        assertEquals(1_000L, isoInstantToEpochMillis("1970-01-01T00:00:01Z"))
        // 2026-01-31T09:15:00Z
        assertEquals(1_769_850_900_000L, isoInstantToEpochMillis("2026-01-31T09:15:00Z"))
    }

    @Test
    fun `the fraction of a second is read`() {
        assertEquals(1_500L, isoInstantToEpochMillis("1970-01-01T00:00:01.500Z"))
    }

    @Test
    fun `an offset is applied`() {
        // 09:15 in a zone one hour ahead of UTC is 08:15 UTC.
        assertEquals(
            isoInstantToEpochMillis("2026-01-31T08:15:00Z"),
            isoInstantToEpochMillis("2026-01-31T09:15:00+01:00"),
        )
    }

    @Test
    fun `a leap day is a day`() {
        val leapDay = isoInstantToEpochMillis("2024-02-29T00:00:00Z")!!
        val nextDay = isoInstantToEpochMillis("2024-03-01T00:00:00Z")!!
        assertEquals(86_400_000L, nextDay - leapDay)
    }

    @Test
    fun `what is not an instant is not one`() {
        assertNull(isoInstantToEpochMillis(null))
        assertNull(isoInstantToEpochMillis(""))
        assertNull(isoInstantToEpochMillis("31/01/2026"))
        assertNull(isoInstantToEpochMillis("2026-01-31"))
    }

    @Test
    fun `a retry after in seconds is read`() {
        assertEquals(5L, parseRetryAfterSeconds("5", 0))
        assertEquals(0L, parseRetryAfterSeconds("0", 0))
    }

    @Test
    fun `a retry after as a date is read`() {
        val now = isoInstantToEpochMillis("1994-11-06T08:49:00Z")!!
        assertEquals(37L, parseRetryAfterSeconds("Sun, 06 Nov 1994 08:49:37 GMT", now))
    }

    @Test
    fun `a retry after that says nothing is nothing`() {
        assertNull(parseRetryAfterSeconds(null, 0))
        assertNull(parseRetryAfterSeconds("", 0))
        assertNull(parseRetryAfterSeconds("soon", 0))
    }
}
