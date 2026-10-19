package dev.averyn.android

import android.app.Application
import android.os.BatteryManager
import dev.averyn.android.auth.AuthManager
import dev.averyn.android.ble.BleHrAdapter
import dev.averyn.android.consent.ConsentClient
import dev.averyn.sensors.HeartRateSample
import dev.averyn.sensors.RrInterval
import dev.averyn.tracking.ActivityRecorder
import dev.averyn.tracking.ActivityStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

    /** The scope the heart-rate sidecar needs: without it the raw intervals are never recorded. */
    private val rrConsentScope = "rr_recording"

    private val consentClient by lazy { ConsentClient(auth) { auth.serverUrl } }

    /** Work that outlives an activity: the consent refresh runs here. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Asks the server which consents the user has and caches them (ADR-0021). The server is authoritative:
     * a revocation there stops the recording here at the next refresh.
     */
    fun refreshConsents() {
        appScope.launch {
            val granted = consentClient.grantedScopes()
            setRrConsent(granted.contains(rrConsentScope))
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Anything finished earlier and not uploaded yet (offline, killed, signed out) goes up now.
        if (auth.serverUrl != null) enqueueSync(this)
        refreshConsents()
    }
}

/** Forwards HR samples from the adapter to the recorder while an activity is RECORDING, and stores RR
 *  intervals in the consent-gated sidecar. */
fun AverynApplication.feedHeartRate(sample: HeartRateSample) {
    recorder.onHeartRate(sample)
}

fun AverynApplication.feedRrInterval(rr: RrInterval) {
    if (!hasRrConsent) return
    val activityId = recorder.currentActivityId ?: return
    // The sidecar gets its own header before the first interval: a file without one has no activity to
    // belong to (ADR-0020).
    recorder.currentMetadata()?.let { store.ensureRrMetadata(it) }
    store.appendRrInterval(activityId, rr)
}
