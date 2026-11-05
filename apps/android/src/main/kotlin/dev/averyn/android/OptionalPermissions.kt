package dev.averyn.android

import android.Manifest
import android.os.Build

/**
 * The permissions a recording can do without but is better with: notifications (Android 13+) and the Bluetooth ones
 * for the heart-rate strap (Android 12+, ADR-0020). Refusing them never blocks a recording; not asking for them did
 * hide the strap from whoever had already granted the location (S15).
 */
internal fun optionalPermissionsMissing(
    sdk: Int,
    isGranted: (String) -> Boolean,
): List<String> =
    buildList {
        if (sdk >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        if (sdk >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }.filterNot(isGranted)

/**
 * Whether "Allow all the time" is worth asking now: only where it exists (Android 10+), only once the precise location
 * is there (Android 11+ sends the user to Settings and refuses the two in one request), and only if it is not granted.
 */
internal fun backgroundLocationWanted(
    sdk: Int,
    hasPreciseLocation: Boolean,
    backgroundGranted: Boolean,
): Boolean = sdk >= Build.VERSION_CODES.Q && hasPreciseLocation && !backgroundGranted
