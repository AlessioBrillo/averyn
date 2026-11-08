package dev.averyn.android

import android.app.Application
import android.os.BatteryManager
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.averyn.android.auth.AuthManager
import dev.averyn.android.ble.BandPrefs
import dev.averyn.android.ble.BleHrAdapter
import dev.averyn.android.ble.HrSensor
import dev.averyn.android.ble.SynapseBleAdapter
import dev.averyn.api.ConsentGrant
import dev.averyn.api.UnitSystem
import dev.averyn.client.HistoryRepository
import dev.averyn.sensors.HeartRateSample
import dev.averyn.sensors.RrInterval
import dev.averyn.tracking.ActivityRecorder
import dev.averyn.tracking.ActivityStore
import dev.averyn.tracking.DroppedInputReason
import dev.averyn.tracking.LiveSnapshot
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class AverynApplication : Application() {
    val auth: AuthManager by lazy { AuthManager(this) { clearServerCache() } }
    val store: ActivityStore by lazy { ActivityStore(Path(filesDir.absolutePath, "activities")) }

    /** More than one reader of the recorder's state (the screen, the service): a single slot made one erase the other. */
    private val recorderListeners = CopyOnWriteArrayList<(LiveSnapshot) -> Unit>()

    /** Registers [listener]; the returned function removes it. */
    fun addRecorderListener(listener: (LiveSnapshot) -> Unit): () -> Unit {
        recorderListeners += listener
        return { recorderListeners -= listener }
    }

    /**
     * One recovery at a time for the whole process. The screen and the service (restarted by the system) can both
     * find an interrupted activity at launch: two replays of the same file would log a second RECOVERED event, and
     * the recorder is not thread-safe.
     */
    val recoveryInFlight = AtomicBoolean(false)

    val recorder: ActivityRecorder by lazy {
        ActivityRecorder(store).apply {
            listener = { snapshot -> recorderListeners.forEach { it(snapshot) } }
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
                profileDirectory = File(filesDir, "profile").absolutePath,
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

    /**
     * The unit system the numbers are written in (TDD-0012). It is read from the profile of the account and
     * remembered in the copy of the server, so it holds offline; a state, so every screen follows a change.
     */
    var units: UnitSystem by mutableStateOf(UnitSystem.METRIC)

    /** BLE heart-rate adapter (standard GATT 0x180D/0x2A37). Created lazily so the app installs on
     *  devices without Bluetooth too (the adapter itself is harmless when Bluetooth is off). */
    val hrAdapter: BleHrAdapter by lazy { BleHrAdapter(this) }

    /** What the user chose about a SYNAPSE band (ADR-0030): the sensor, its address, raw over mobile data. */
    val bandPrefs: BandPrefs by lazy { BandPrefs(this) }

    /** The SYNAPSE band: heart rate for the recording plus the two sidecars, under the consents below. */
    val synapseAdapter: SynapseBleAdapter by lazy { SynapseBleAdapter(this, store, bandPrefs) }

    /** The consents of the band's signals as the server last said (ADR-0021); none until the first answer. */
    @Volatile
    var synapseConsents: dev.averyn.sensors.SynapseConsents = dev.averyn.sensors.SynapseConsents.NONE
        private set

    /** The sensor of the next recording: the band if the user chose it, otherwise the heart-rate strap. */
    val sensor: HrSensor
        get() =
            if (bandPrefs.useBand) {
                synapseAdapter.also {
                    it.consents = synapseConsents
                    it.recordRrIntervals = hasRrConsent // fail closed: never what it was when last used
                }
            } else {
                hrAdapter
            }

    /** Whether RR intervals (Health class) may be recorded right now. Consent is server-authoritative
     *  (ADR-0021); this cache is populated from GET /v1/consents and failed closed (false) until then. */
    var hasRrConsent = false
        private set

    fun setRrConsent(granted: Boolean) {
        hasRrConsent = granted
        hrAdapter.recordRrIntervals = granted
        synapseAdapter.recordRrIntervals = granted
    }

    /** The scope the heart-rate sidecar needs: without it the raw intervals are never recorded. */
    private val rrConsentScope = "rr_recording"

    /** Work that outlives an activity: the consent refresh runs here. */
    private val appScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO +
                // Net for background work that throws: the class only, never the message (it can carry a URL).
                CoroutineExceptionHandler {
                    _,
                    e,
                    ->
                    Log.w("Averyn", "background task failed: ${e.javaClass.simpleName}")
                },
        )

    /**
     * Asks the server which consents the user has and caches them (ADR-0021). The server is authoritative:
     * a revocation there stops the recording here at the next refresh. Uses the shared client so the
     * consent read path is implemented once, not per platform.
     */
    fun refreshConsents() {
        appScope.launch {
            val scopes: Set<String> =
                try {
                    // freshAccessToken() throws on transient trouble (no network): inside the try, like the call
                    // below, so a signed-in phone that is offline keeps recording instead of dying here.
                    val token = auth.freshAccessToken() ?: return@launch
                    val client = history().client
                    val result = client.consents(token)
                    val grants = result.value ?: listOf<ConsentGrant>()
                    grants.map { it.scope }.toSet()
                } catch (e: Exception) {
                    // The answer is the server's; without it nothing is granted, and the app keeps recording.
                    emptySet<String>()
                }
            setRrConsent(scopes.contains(rrConsentScope))
            synapseConsents =
                dev.averyn.sensors.SynapseConsents
                    .fromScopes(scopes)
            if (bandPrefs.useBand) synapseAdapter.consents = synapseConsents
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Anything finished earlier and not uploaded yet (offline, killed, signed out) goes up now.
        if (auth.serverUrl != null) enqueueSync(this)
        refreshConsents()
        // The activities recorded by an older build have no local summary yet: they get one, in the background
        // (TDD-0011), so their numbers are there even without a connection.
        appScope.launch {
            // Killed before the first fix: nothing was recorded, and the file would stay for good.
            // The activity this process is recording (a Start tapped at once) is not that file.
            store.discardEmptyPreparing(exceptId = recorder.currentActivityId)
            store.backfillLocalSummaries()
        }
    }
}

/** Forwards HR samples from the adapter to the recorder while an activity is RECORDING, and stores RR
 *  intervals in the consent-gated sidecar. */
fun AverynApplication.feedHeartRate(sample: HeartRateSample) {
    recorder.onHeartRate(sample)
}

fun AverynApplication.feedInvalidHeartRate() {
    recorder.recordDroppedInput(DroppedInputReason.INVALID_HEART_RATE)
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
