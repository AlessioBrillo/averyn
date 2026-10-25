package dev.averyn.client

import dev.averyn.api.UnitSystem
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val METERS_PER_MILE = 1609.344
private const val METERS_PER_FOOT = 0.3048

/**
 * How the phones write a distance, a pace, a speed and an elevation in the unit system of the profile
 * (TDD-0012). One implementation for both apps: the web has its own, with the same conversions
 * (`apps/web/src/format.ts`), and the tests of both pin the same examples.
 *
 * Display only: every metric is computed by the shared code or by the server; this turns numbers into text.
 */
class UnitFormat(
    val units: UnitSystem = UnitSystem.METRIC,
) {
    private val imperial get() = units == UnitSystem.IMPERIAL

    /** `km` or `mi`. */
    val distanceUnit: String get() = if (imperial) "mi" else "km"

    /** `m` or `ft`. */
    val elevationUnit: String get() = if (imperial) "ft" else "m"

    /** A distance: metres below a kilometre (metric), otherwise two decimals of km or mi. */
    fun distance(meters: Double): String =
        when {
            imperial -> "${fixed(meters / METERS_PER_MILE, 2)} mi"
            meters >= 1000 -> "${fixed(meters / 1000, 2)} km"
            else -> "${meters.roundToInt()} m"
        }

    /** m:ss per km (or per mile). */
    fun pace(secondsPerKm: Double): String {
        val seconds = (if (imperial) secondsPerKm * METERS_PER_MILE / 1000 else secondsPerKm).roundToInt()
        return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')} /$distanceUnit"
    }

    /** km/h or mph, one decimal. */
    fun speed(metersPerSecond: Double): String =
        if (imperial) {
            "${fixed(metersPerSecond * 3600 / METERS_PER_MILE, 1)} mph"
        } else {
            "${fixed(metersPerSecond * 3.6, 1)} km/h"
        }

    /** An altitude or a climb in whole m or ft, with the sign when [signed] ("+120 m", "-35 m"). */
    fun elevation(
        meters: Double,
        signed: Boolean = false,
    ): String {
        val value = (if (imperial) meters / METERS_PER_FOOT else meters).roundToInt()
        val sign = if (signed && value > 0) "+" else ""
        return "$sign$value $elevationUnit"
    }
}

/** [value] with exactly [decimals] decimals, without `String.format` (not in common Kotlin). */
internal fun fixed(
    value: Double,
    decimals: Int,
): String {
    var scale = 1L
    repeat(decimals) { scale *= 10 }
    val scaled = (abs(value) * scale).roundToLong()
    val whole = scaled / scale
    val fraction = (scaled % scale).toString().padStart(decimals, '0')
    val sign = if (value < 0 && scaled != 0L) "-" else ""
    return if (decimals == 0) "$sign$whole" else "$sign$whole.$fraction"
}
