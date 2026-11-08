package dev.averyn.android.ble

import android.content.Context

/**
 * What the user chose about a SYNAPSE band, kept on this device only (it is a setting, not health data):
 * whether the band is the recording sensor, its address once found, and whether the raw signals may go up over
 * mobile data (off by default, ADR-0030).
 */
class BandPrefs(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("averyn_band", Context.MODE_PRIVATE)

    /** The band replaces the heart-rate strap as the sensor of a recording. */
    var useBand: Boolean
        get() = prefs.getBoolean("use_band", false)
        set(value) = prefs.edit().putBoolean("use_band", value).apply()

    /** The address of the band found by the first scan; empty until then. Forgetting it makes the next scan start over. */
    var address: String?
        get() = prefs.getString("address", null)
        set(value) = prefs.edit().putString("address", value).apply()

    /** The raw signals may go up over mobile data. Without it they wait for an unmetered network. */
    var rawOnMobile: Boolean
        get() = prefs.getBoolean("raw_on_mobile", false)
        set(value) = prefs.edit().putBoolean("raw_on_mobile", value).apply()
}
