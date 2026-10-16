package dev.averyn.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import dev.averyn.tracking.DeviceInfo

/** Precise location is what recording needs; `ACCESS_COARSE_LOCATION` alone (the "approximate" grant) is not enough. */
internal fun hasPreciseLocation(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

private fun hasApproximateLocation(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

internal fun locationPermissionLabel(context: Context): String =
    when {
        hasPreciseLocation(context) -> "PRECISE"
        hasApproximateLocation(context) -> "APPROXIMATE"
        else -> "DENIED"
    }

/** True when the system will not put the app to sleep to save battery (what long recordings need). */
internal fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

/** No device identifiers (docs/privacy/data-classification.md) — just what a device-matrix row needs. */
internal fun deviceInfo(context: Context): DeviceInfo {
    val appVersion =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    val power = context.getSystemService(PowerManager::class.java)
    return DeviceInfo(
        model = Build.MODEL,
        osVersion = "Android ${Build.VERSION.RELEASE}",
        appVersion = appVersion,
        manufacturer = Build.MANUFACTURER,
        buildId = BuildConfig.COMMIT_ID,
        locationPermission = locationPermissionLabel(context),
        powerSaverOn = power.isPowerSaveMode,
        batteryOptimisationExempt = power.isIgnoringBatteryOptimizations(context.packageName),
    )
}
