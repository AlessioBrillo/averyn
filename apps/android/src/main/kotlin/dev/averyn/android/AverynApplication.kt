package dev.averyn.android

import android.app.Application
import android.os.BatteryManager
import dev.averyn.android.auth.AuthManager
import dev.averyn.android.ble.BleHrAdapter
import dev.averyn.client.HistoryRepository
import dev.averyn.sensors.HeartRateSample
import dev.averyn.sensors.RrInterval
import dev.averyn.tracking.ActivityRecorder
import dev.averyn.tracking.ActivityStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import java.io.File

class AverynApplication : Application() {
    val auth: AuthManager by lazy { AuthManager(this) { clearServerCache() } }
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

    /** The copy of the server on this device (TDD-0011): its own directory, never the store of the activities. */
    private val serverCacheDirectory: File by lazy { File(filesDir, "server-cache") }

    /** The history of the account: the server, the cache and the device. Rebuilt when the server changes. */
    private var historyRepository: HistoryRepository? = null
    private var historyServerUrl: String? = null

    fun history(): HistoryRepository {
        val server = auth.serverUrl.orEmpty()
        val current = historyRepository
        if (current != null && historyServerUrl == server) return current
        val created =
            HistoryRepository.create(
                store = store,
                cacheDirectory = serverCacheDirectory.absolutePath,
                serverUrl = server,
                nowMs = { System.currentTimeMillis() },
            )
        historyRepository = created
        historyServerUrl = server
        return created
    }

    /**
     * Throws the copy of the server away: the session ended, or the server changed.
     *
     * The copy holds the coordinates of the activities of the account: it belongs to the session.
     */
    fun clearServerCache() {
        serverCacheDirectory.deleteRecursively()
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

    /** Work that outlives an activity: the consent refresh runs here. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Asks the server which consents the user has and caches them (ADR-0021). The server is authoritative:
     * a revocation there stops the recording here at the next refresh. Uses the shared client so the
     * consent read path is implemented once, not per platform.
     */
    fun refreshConsents() {
        appScope.launch {
            val granted =
                try {
                    val token = auth.freshAccessToken() ?: return@launch
                    val server = auth.serverUrl ?: return@launch
                    val client = history().client
                    client.consents(token).value?.map { it.scope }?.toSet() ?: emptySet()
                } catch (e: Exception) {
                    // The answer is the server's; without it nothing is granted, and the app keeps recording.
                    emptySet()
                }
            setRrConsent(granted.contains(rrConsentScope))
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Anything finished earlier and not uploaded yet (offline, killed, signed out) goes up now.
        if (auth.serverUrl != null) enqueueSync(this)
        refreshConsents()
        // The activities recorded by an older build have no local summary yet: they get one, in the background
        // (TDD-0011), so their numbers are there even without a connection.
        appScope.launch { store.backfillLocalSummaries() }
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
    // belong to (ADR-0020). The check is a file existence test, not a read of the file, for every interval.
    if (!store.hasRr(activityId)) {
        recorder.currentMetadata()?.let { store.createRrMetadata(it) }
    }
    store.appendRrInterval(activityId, rr)
}
