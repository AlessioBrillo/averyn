package dev.averyn.android

import android.app.Application
import android.os.BatteryManager
import dev.averyn.android.auth.AuthManager
import dev.averyn.android.ble.BleHrAdapter
import dev.averyn.sensors.HeartRateSample
import dev.averyn.sensors.RrInterval
import dev.averyn.tracking.ActivityRecorder
import dev.averyn.tracking.ActivityStore
import kotlinx.io.files.Path

class AverynApplication : Application() {
    val auth: AuthManager by lazy { AuthManager(this) }
    val store: ActivityStore by lazy { ActivityStore(Path(filesDir.absolutePath, "activities")) }
    val recorder: ActivityRecorder by lazy {
        ActivityRecorder(store).apply {
            batteryPercent = {
                val percent =
                    getSystemService(BatteryManager::class.java)
                        .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                percent.takeIf { it in 0..100 } // the API returns a negative value when unsupported
            }
        }
    }

    /** BLE heart-rate adapter (standard GATT 0x180D/0x2A37). Created lazily so the app installs on
     *  devices without Bluetooth too (the adapter itself is harmless when Bluetooth is off). */
    val hrAdapter: BleHrAdapter by lazy { BleHrAdapter(this) }

    /** Whether RR intervals (Health class) may be recorded right now. Consent is server-authoritative
     *  (ADR-0021); this cache is populated from GET /v1/consents and failed closed (false) until then. */
    var hasRrConsent = false
        private set

    fun setRrConsent(granted: Boolean) {
        hasRrConsent = granted
        hrAdapter.recordRrIntervals = granted
    }

    override fun onCreate() {
        super.onCreate()
        // Anything finished earlier and not uploaded yet (offline, killed, signed out) goes up now.
        if (auth.serverUrl != null) enqueueSync(this)
    }
}

/** Forwards HR samples from the adapter to the recorder while an activity is RECORDING, and stores RR
 *  intervals in the consent-gated sidecar. */
fun AverynApplication.feedHeartRate(sample: HeartRateSample) {
    recorder.onHeartRate(sample)
}

fun AverynApplication.feedRrInterval(rr: RrInterval) {
    if (!hasRrConsent) return
    recorder.currentActivityId?.let { store.appendRrInterval(it, rr) }
}
