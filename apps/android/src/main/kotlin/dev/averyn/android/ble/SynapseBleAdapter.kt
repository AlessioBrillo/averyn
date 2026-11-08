package dev.averyn.android.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dev.averyn.sensors.HeartRateSample
import dev.averyn.sensors.ParseResult
import dev.averyn.sensors.RrInterval
import dev.averyn.sensors.SYNAPSE_CONSUMER_SERVICE_UUID
import dev.averyn.sensors.SYNAPSE_CONTROL_POINT_CHARACTERISTIC_UUID
import dev.averyn.sensors.SYNAPSE_REQUESTED_MTU
import dev.averyn.sensors.SYNAPSE_RESEARCH_SERVICE_UUID
import dev.averyn.sensors.SynapseConsents
import dev.averyn.sensors.SynapseControl
import dev.averyn.sensors.SynapseRecorder
import dev.averyn.sensors.parseHeartRateFrames
import dev.averyn.tracking.ActivityStore
import dev.averyn.tracking.SynapseSidecarKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private val HEART_RATE_SERVICE = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
private val HEART_RATE_MEASUREMENT = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
private const val SCAN_TIMEOUT_MS = 15_000L
private const val STEP_TIMEOUT_MS = 5_000L

/** MTU exchange plus service discovery; a band that does not answer in this time is reconnected. */
private const val SERVICES_TIMEOUT_MS = 15_000L

/**
 * The SYNAPSE band over BLE (ADR-0030), the Android half. One connection does three things:
 *
 * - the standard heart-rate service (`0x180D`) feeds the recording exactly as [BleHrAdapter] does for a strap, so
 *   the tracking does not know which sensor it has;
 * - the consumer service's feature and status notifications, and, under `raw_signals`, the research service's raw
 *   streams, go to a [SynapseRecorder], which writes the two sidecars of the activity under the user's consents
 *   (masking before the disk, ADR-0030);
 * - on every connection the band gets its wall clock (`set_time`) and the raw streams are started or stopped to
 *   match the consent (a new connection starts them running).
 *
 * The connection is set up one GATT operation at a time ([GattStepper]); an MTU of [SYNAPSE_REQUESTED_MTU] is
 * asked first, since a status record needs 43 bytes and a raw frame up to 247.
 *
 * Nothing here has run against a band: the sequence follows the contract (`gatt_v2.json`), not an observation.
 */
@SuppressLint("MissingPermission")
class SynapseBleAdapter(
    private val context: Context,
    private val store: ActivityStore,
    private val prefs: BandPrefs,
) : HrSensor {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val scanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner

    override var recordRrIntervals = false
    override val supportsRr: Boolean get() = true
    override val isAvailable: Boolean
        get() = bluetoothAdapter != null && bluetoothAdapter.isEnabled && hasBluetoothPermission()

    override var onSample: ((HeartRateSample) -> Unit)? = null
    override var onRrInterval: ((RrInterval) -> Unit)? = null
    override var onInvalid: (() -> Unit)? = null

    /**
     * The consents in force, set by the application after each read of the server's answer. The recorder reads them
     * before every write; a raw consent withdrawn mid-recording also stops the band's raw streams. One granted
     * mid-recording takes effect on the next connection (the streams are subscribed when connecting).
     */
    @Volatile
    var consents: SynapseConsents = SynapseConsents.NONE
        set(value) {
            val rawRevoked = field.rawSignals && !value.rawSignals
            field = value
            recorder?.consents = value
            if (rawRevoked) writeControlAsync(SynapseControl.stop())
        }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var connectJob: Job? = null
    private val lock = Any()
    private var scanCallback: ScanCallback? = null

    @Volatile
    private var currentGatt: BluetoothGatt? = null

    @Volatile
    private var stepper = GattStepper()

    @Volatile
    private var recorder: SynapseRecorder? = null
    private var sessionId: String = ""
    private var startNanos = 0L

    /** Bumped by [stop]: a start that began before it must not connect after it. */
    private val generation = AtomicInteger()
    private val starting = AtomicBoolean(false)

    override suspend fun start(sessionId: String) {
        withContext(Dispatchers.Default) {
            if (sessionId.isEmpty() || connectJob?.isActive == true) return@withContext
            if (!starting.compareAndSet(false, true)) return@withContext // a scan is already looking
            try {
                startLocked(sessionId)
            } finally {
                starting.set(false)
            }
        }
    }

    private suspend fun startLocked(sessionId: String) {
        val started = generation.get()
        run {
            if (recorder == null || sessionId != this@SynapseBleAdapter.sessionId) {
                this@SynapseBleAdapter.sessionId = sessionId
                // t_rx_ns counts from the start of the recording, also after a restart of the process in
                // the middle of it: the sidecar is appended to, and its clock must not jump back to zero.
                val sinceStartMs =
                    store.metadataOf(sessionId)?.let { System.currentTimeMillis() - it.startedAtMs }
                        ?: 0L
                startNanos = SystemClock.elapsedRealtimeNanos() - sinceStartMs.coerceAtLeast(0L) * 1_000_000L
                recorder =
                    SynapseRecorder(
                        store.synapseSink(sessionId, SynapseSidecarKind.FEATURES),
                        store.synapseSink(sessionId, SynapseSidecarKind.RAW),
                        consents,
                    )
            }
            val device =
                try {
                    findBand()
                } catch (e: IllegalStateException) {
                    null // not found in time: the recording goes on without it, and the next call looks again
                } catch (e: SecurityException) {
                    null
                } ?: return
            if (generation.get() != started) return // stopped while looking: do not connect
            prefs.address = device.address
            connectJob = scope.launch { connectAndStream(device) }
        }
    }

    override suspend fun stop() {
        generation.incrementAndGet()
        connectJob?.cancel()
        connectJob = null
        synchronized(lock) {
            currentGatt?.close()
            currentGatt = null
        }
        stopScanning()
        recorder = null
        sessionId = ""
    }

    private suspend fun findBand(): BluetoothDevice? {
        prefs.address?.let { address -> bluetoothAdapter?.getRemoteDevice(address)?.let { return it } }
        if (scanner == null || !hasBluetoothPermission()) return null
        val found = CompletableDeferred<BluetoothDevice>()
        val filter =
            ScanFilter
                .Builder()
                .setServiceUuid(
                    ParcelUuid(UUID.fromString(SYNAPSE_CONSUMER_SERVICE_UUID)),
                ).build()
        val settings =
            ScanSettings
                .Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build()
        val callback =
            object : ScanCallback() {
                override fun onScanResult(
                    callbackType: Int,
                    result: ScanResult,
                ) {
                    stopScanning()
                    if (!found.isCompleted) found.complete(result.device)
                }
            }
        scanCallback = callback
        scanner.startScan(listOf(filter), settings, callback)
        scope.launch {
            delay(SCAN_TIMEOUT_MS)
            if (!found.isCompleted) {
                stopScanning()
                found.completeExceptionally(IllegalStateException("no SYNAPSE band found"))
            }
        }
        return found.await()
    }

    private fun stopScanning() {
        val callback = scanCallback ?: return
        scanCallback = null
        runCatching { scanner?.stopScan(callback) }
    }

    private fun hasBluetoothPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    private suspend fun connectAndStream(device: BluetoothDevice) {
        var attempt = 0
        while (true) {
            synchronized(lock) {
                currentGatt?.close()
                currentGatt = null
            }
            val steps = GattStepper().also { stepper = it }
            val connected = CompletableDeferred<Boolean>()
            val servicesOk = CompletableDeferred<Boolean>()
            val disconnected = CompletableDeferred<Unit>()
            val callback =
                object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(
                        gatt: BluetoothGatt,
                        status: Int,
                        newState: Int,
                    ) {
                        when (newState) {
                            BluetoothGatt.STATE_CONNECTED -> {
                                if (!connected.isCompleted) connected.complete(status == BluetoothGatt.GATT_SUCCESS)
                                // The MTU first: the services are discovered once the answer is in (onMtuChanged).
                                if (!gatt.requestMtu(SYNAPSE_REQUESTED_MTU)) gatt.discoverServices()
                            }
                            BluetoothGatt.STATE_DISCONNECTED -> {
                                if (!connected.isCompleted) connected.complete(false)
                                if (!servicesOk.isCompleted) servicesOk.complete(false)
                                if (!disconnected.isCompleted) disconnected.complete(Unit)
                            }
                        }
                    }

                    override fun onMtuChanged(
                        gatt: BluetoothGatt,
                        mtu: Int,
                        status: Int,
                    ) {
                        // Whatever the answer, the band works at the MTU it got (a lower one only truncates notifications).
                        gatt.discoverServices()
                    }

                    override fun onServicesDiscovered(
                        gatt: BluetoothGatt,
                        status: Int,
                    ) {
                        if (!servicesOk.isCompleted) {
                            servicesOk.complete(
                                status == BluetoothGatt.GATT_SUCCESS &&
                                    gatt.getService(UUID.fromString(SYNAPSE_CONSUMER_SERVICE_UUID)) != null,
                            )
                        }
                    }

                    override fun onDescriptorWrite(
                        gatt: BluetoothGatt,
                        descriptor: BluetoothGattDescriptor,
                        status: Int,
                    ) = steps.done(status == BluetoothGatt.GATT_SUCCESS)

                    override fun onCharacteristicWrite(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        status: Int,
                    ) = steps.done(status == BluetoothGatt.GATT_SUCCESS)

                    override fun onCharacteristicChanged(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                    ) {
                        val data = characteristic.value ?: return
                        if (characteristic.uuid == HEART_RATE_MEASUREMENT) {
                            onHeartRate(data)
                        } else {
                            val sinceStart = (SystemClock.elapsedRealtimeNanos() - startNanos).coerceAtLeast(0L)
                            recorder?.onNotify(characteristic.uuid.toString(), data, sinceStart)
                        }
                    }
                }
            val gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            synchronized(lock) { currentGatt = gatt }
            if (gatt == null ||
                !connected.awaitOrFalse(GATT_CONNECT_TIMEOUT_MS) ||
                !(withTimeoutOrNull(SERVICES_TIMEOUT_MS) { servicesOk.await() } ?: false) ||
                !setUp(gatt, steps)
            ) {
                delay(reconnectBackoffMs(++attempt))
                continue
            }
            attempt = 0 // streaming: the budget of reconnects is for failures in a row
            disconnected.await()
            delay(reconnectBackoffMs(++attempt))
        }
    }

    /** Subscribes, then gives the band its clock and starts or stops the raw streams. False when the feature stream is lost. */
    private suspend fun setUp(
        gatt: BluetoothGatt,
        steps: GattStepper,
    ): Boolean {
        val rawAllowed = consents.rawSignals
        var featureOk = false
        for (uuid in synapseNotifyPlan(rawAllowed)) {
            val ok = steps.step(STEP_TIMEOUT_MS) { enableNotifications(gatt, characteristic(gatt, uuid)) }
            if (uuid == dev.averyn.sensors.SYNAPSE_FEATURE_CHARACTERISTIC_UUID) featureOk = ok
        }
        // The heart rate for the recording itself; a band without the standard service still records its sidecars.
        gatt.getService(HEART_RATE_SERVICE)?.getCharacteristic(HEART_RATE_MEASUREMENT)?.let { hr ->
            steps.step(STEP_TIMEOUT_MS) { enableNotifications(gatt, hr) }
        }
        steps.step(STEP_TIMEOUT_MS) { writeControl(gatt, SynapseControl.setTime(System.currentTimeMillis())) }
        steps.step(STEP_TIMEOUT_MS) {
            writeControl(gatt, if (rawAllowed) SynapseControl.start() else SynapseControl.stop())
        }
        return featureOk
    }

    private fun characteristic(
        gatt: BluetoothGatt,
        uuid: String,
    ): BluetoothGattCharacteristic? {
        val id = UUID.fromString(uuid)
        val service =
            if (uuid in
                rawServiceCharacteristics
            ) {
                SYNAPSE_RESEARCH_SERVICE_UUID
            } else {
                SYNAPSE_CONSUMER_SERVICE_UUID
            }
        return gatt.getService(UUID.fromString(service))?.getCharacteristic(id)
    }

    private val rawServiceCharacteristics =
        dev.averyn.sensors.SYNAPSE_RESEARCH_CHARACTERISTIC_UUIDS.values
            .toSet()

    private fun enableNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic?,
    ): Boolean {
        if (characteristic == null || !gatt.setCharacteristicNotification(characteristic, true)) return false
        val descriptor = characteristic.getDescriptor(CCCD) ?: return false
        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        return gatt.writeDescriptor(descriptor)
    }

    private fun writeControl(
        gatt: BluetoothGatt,
        bytes: ByteArray,
    ): Boolean {
        val control =
            gatt
                .getService(UUID.fromString(SYNAPSE_CONSUMER_SERVICE_UUID))
                ?.getCharacteristic(UUID.fromString(SYNAPSE_CONTROL_POINT_CHARACTERISTIC_UUID))
                ?: return false
        control.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        control.setValue(bytes)
        return gatt.writeCharacteristic(control)
    }

    /** A control write outside the connection sequence (a consent withdrawn while streaming). Best effort. */
    private fun writeControlAsync(bytes: ByteArray) {
        val gatt = currentGatt ?: return
        val steps = stepper
        scope.launch { steps.step(STEP_TIMEOUT_MS) { writeControl(gatt, bytes) } }
    }

    private fun onHeartRate(data: ByteArray) {
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val (results, _) = parseHeartRateFrames(data, 0, elapsed, now)
        for (result in results) {
            if (result is ParseResult.Invalid) onInvalid?.invoke()
            if (result is ParseResult.Complete) {
                result.heartRate?.let { onSample?.invoke(it) }
                if (recordRrIntervals) for (rr in result.rrIntervals) onRrInterval?.invoke(rr)
            }
        }
    }
}
