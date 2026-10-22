package dev.averyn.client

/*
 * The time helpers of the client: the instants of the contract are ISO-8601 strings, and the server's
 * `Retry-After` is either a number of seconds or an HTTP date.
 *
 * ponytail: a hand-written parser instead of a datetime library. The format is fixed (the server writes
 * `Instant.toString()`), the arithmetic is the well-known days-from-civil formula, and the whole thing is
 * covered by tests: one dependency less for two functions.
 */

/** `2026-01-31T09:15:00Z`, with an optional fraction of a second and an optional UTC offset. */
private val ISO_INSTANT =
    Regex("""^(\d{4})-(\d{2})-(\d{2})[Tt ](\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(Z|z|[+-]\d{2}:?\d{2})?$""")

/** `Sun, 06 Nov 1994 08:49:37 GMT`, the date format of `Retry-After`. */
private val HTTP_DATE =
    Regex("""^[A-Za-z]{3},\s+(\d{2}) ([A-Za-z]{3}) (\d{4}) (\d{2}):(\d{2}):(\d{2}) GMT$""")

private val MONTHS =
    mapOf(
        "Jan" to 1,
        "Feb" to 2,
        "Mar" to 3,
        "Apr" to 4,
        "May" to 5,
        "Jun" to 6,
        "Jul" to 7,
        "Aug" to 8,
        "Sep" to 9,
        "Oct" to 10,
        "Nov" to 11,
        "Dec" to 12,
    )

/**
 * The epoch milliseconds of an ISO-8601 instant, or null when the text is not one.
 *
 * The apps sort and format by this number, and the local store keeps its own timestamps in epoch millis: the
 * two are compared without a second format in between.
 */
fun isoInstantToEpochMillis(text: String?): Long? {
    val match = ISO_INSTANT.matchEntire(text?.trim().orEmpty()) ?: return null
    val (year, month, day, hour, minute, second) = match.destructured
    val fraction = match.groupValues[7]
    val zone = match.groupValues[8]
    val days =
        daysFromCivil(
            year.toInt(),
            month.toInt(),
            day.toInt(),
        )
    var millis =
        days * MILLIS_PER_DAY +
            hour.toLong() * MILLIS_PER_HOUR +
            minute.toLong() * MILLIS_PER_MINUTE +
            second.toLong() * MILLIS_PER_SECOND
    millis += (fraction.padEnd(3, '0').take(3).toLongOrNull() ?: 0L)
    val offsetMinutes = zoneOffsetMinutes(zone) ?: 0
    return millis - offsetMinutes * MILLIS_PER_MINUTE
}

private const val MILLIS_PER_SECOND = 1000L
private const val MILLIS_PER_MINUTE = 60_000L
private const val MILLIS_PER_HOUR = 3_600_000L
private const val MILLIS_PER_DAY = 86_400_000L

/** The offset of the zone in minutes (east of UTC is positive), or null when the zone is unknown. */
private fun zoneOffsetMinutes(zone: String): Int? =
    when {
        zone.isEmpty() -> 0
        zone.equals("Z", ignoreCase = true) -> 0
        zone[0] == '+' -> parseOffset(zone.substring(1))
        zone[0] == '-' -> -parseOffset(zone.substring(1))
        else -> null
    }

private fun parseOffset(text: String): Int {
    val digits = text.replace(":", "")
    if (digits.length != 4) return 0
    val hours = digits.substring(0, 2).toIntOrNull() ?: return 0
    val minutes = digits.substring(2, 4).toIntOrNull() ?: return 0
    return hours * 60 + minutes
}

/**
 * The days since the epoch of a civil date (Howard Hinnant's algorithm): the days of a month, the leap years
 * and the century rules, without a calendar library.
 */
private fun daysFromCivil(
    year: Int,
    month: Int,
    day: Int,
): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yearOfEra = y - era * 400
    val dayOfYear = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return (era * 146097 + dayOfEra - 719468).toLong()
}

/**
 * How long the server asked the client to wait, in seconds, or null when the header says nothing usable.
 *
 * The server answers `Retry-After: 5` when the analysis is being computed (TDD-0011): the app shows "in
 * calcolo" and comes back when the server says so.
 */
fun parseRetryAfterSeconds(
    header: String?,
    nowMs: Long,
): Long? {
    val text = header?.trim().orEmpty()
    if (text.isEmpty()) return null
    text.toLongOrNull()?.let { return it.coerceAtLeast(0) }
    val match = HTTP_DATE.matchEntire(text) ?: return null
    val (day, monthName, year, hour, minute, second) = match.destructured
    val month = MONTHS[monthName] ?: return null
    val days =
        daysFromCivil(
            year.toInt(),
            month,
            day.toInt(),
        )
    val millis =
        days * MILLIS_PER_DAY +
            hour.toLong() * MILLIS_PER_HOUR +
            minute.toLong() * MILLIS_PER_MINUTE +
            second.toLong() * MILLIS_PER_SECOND
    return ((millis - nowMs) / MILLIS_PER_SECOND).coerceAtLeast(0)
}
