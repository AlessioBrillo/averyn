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
import androidx.core.content.ContextCompat
import dev.averyn.sensors.HeartRateSample
import dev.averyn.sensors.ParseResult
import dev.averyn.sensors.RrInterval
import dev.averyn.sensors.SensorAdapter
import dev.averyn.sensors.parseHeartRateFrames
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Standard Bluetooth Heart Rate service. */
private val HEART_RATE_SERVICE = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")

/** Standard Heart Rate Measurement characteristic. */
private val HEART_RATE_MEASUREMENT = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")

/** Client Characteristic Configuration descriptor: 0x0001 enables notifications. */
private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/** How long to scan before giving up and reporting the sensor as unavailable. */
private val SCAN_TIMEOUT_MS = 15_000L

/** Reconnect backoff: 1s, 2s, 4s, 8s, then stop. */
private val RECONNECT_BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000, 8_000)

/**
 * Android implementation of [SensorAdapter] over the standard BLE Heart Rate service (`0x180D` /
 * `0x2A37`). No vendor-specific code: any strap exposing the standard service works, Synapse included
 * once its firmware exposes the standard service (ADR-0014, ADR-0020).
 *
 * Platform BLE is touch-only here: `shared/` receives already-parsed [HeartRateSample]/[RrInterval]
 * values. [recordRrIntervals] gates whether RR intervals (Health class, consent-gated, ADR-0021) are
 * forwarded — the caller sets it from the cached consent before [start] (fail-closed: default false).
 *
 * The Bluetooth permissions are runtime permissions on Android 12+: they are checked in
 * [hasBluetoothPermission] before anything is scanned or connected, and the calls that can still be
 * refused are wrapped, so a refused permission means "no strap", never a crash.
 */
@SuppressLint("MissingPermission")
class BleHrAdapter(
    private val context: Context,
) : SensorAdapter {
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val scanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner

    /** Set by the caller from the cached consent before [start] (fail-closed: default false). */
    var recordRrIntervals = false

    override val consentRequired: Boolean
        get() = true // this adapter can produce RR intervals (Health class); consent gates them.

    override val isAvailable: Boolean
        get() = bluetoothAdapter != null && bluetoothAdapter.isEnabled && hasBluetoothPermission()

    override var onSample: ((HeartRateSample) -> Unit)? = null
    override var onRrInterval: ((RrInterval) -> Unit)? = null

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var connectJob: Job? = null
    private val lock = Any()

    /** Last device we connected to, so a restart after kill can reconnect to the same strap. */
    private var lastDeviceAddress: String? = null

    /** The callback the running scan was started with: only that one stops it. */
    private var scanCallback: ScanCallback? = null

    private var currentGatt: BluetoothGatt? = null

    override suspend fun start(sessionId: String) {
        withContext(Dispatchers.Default) {
            if (connectJob?.isActive == true) return@withContext
            val device =
                try {
                    findHeartRateDevice()
                } catch (e: IllegalStateException) {
                    // No sensor found, or the Bluetooth permissions are missing: the strap is optional, the
                    // recording continues without it.
                    null
                } catch (e: SecurityException) {
                    // The permission was refused between the check and the call.
                    null
                } ?: return@withContext
            lastDeviceAddress = device.address
            connectJob = scope.launch { connectAndStream(device) }
        }
    }

    override suspend fun stop() {
        connectJob?.cancel()
        connectJob = null
        synchronized(lock) {
            currentGatt?.close()
            currentGatt = null
        }
        stopScanning()
    }

    // -------------------------------------------------------------------------------------------
    // Discovery
    // -------------------------------------------------------------------------------------------

    private suspend fun findHeartRateDevice(): BluetoothDevice? {
        // Prefer reconnecting to the last strap we successfully paired with.
        lastDeviceAddress?.let { address ->
            bluetoothAdapter?.getRemoteDevice(address)?.let { return it }
        }

        if (scanner == null) return null
        if (!hasBluetoothPermission()) return null
        val deviceResult = CompletableDeferred<BluetoothDevice>()
        val filter =
            ScanFilter
                .Builder()
                .setServiceUuid(ParcelUuid(HEART_RATE_SERVICE))
                .build()
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
                    if (!resultHasHeartRateService(result)) return
                    stopScanning()
                    if (!deviceResult.isCompleted) deviceResult.complete(result.device)
                }
            }
        // The callback the scan was started with is the one that stops it: stopScan() with another one does
        // nothing, and the scanner would keep running in the background.
        scanCallback = callback
        scanner.startScan(listOf(filter), settings, callback)
        scope.launch {
            delay(SCAN_TIMEOUT_MS)
            if (!deviceResult.isCompleted) {
                stopScanning()
                deviceResult.completeExceptionally(IllegalStateException("no heart rate sensor found"))
            }
        }
        return deviceResult.await()
    }

    private fun stopScanning() {
        val callback = scanCallback ?: return
        scanCallback = null
        runCatching { scanner?.stopScan(callback) }
    }

    /**
     * Whether the app may use Bluetooth right now. On Android 12+ the permissions are asked at runtime; without
     * them the scan throws, so the sensor is reported as unavailable instead of crashing the recording.
     */
    private fun hasBluetoothPermission(): Boolean {
        val scan =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        val connect =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        return scan && connect
    }

    private fun resultHasHeartRateService(result: ScanResult): Boolean =
        result.scanRecord?.serviceUuids?.any { it.uuid == HEART_RATE_SERVICE } == true

    // -------------------------------------------------------------------------------------------
    // Connect + stream
    // -------------------------------------------------------------------------------------------

    private suspend fun connectAndStream(device: BluetoothDevice) {
        var attempt = 0
        while (true) {
            synchronized(lock) {
                currentGatt?.close()
                currentGatt = null
            }
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
                                gatt.discoverServices()
                            }
                            BluetoothGatt.STATE_DISCONNECTED -> {
                                if (!connected.isCompleted) connected.complete(false)
                                if (!servicesOk.isCompleted) servicesOk.complete(false)
                                if (!disconnected.isCompleted) disconnected.complete(Unit)
                            }
                        }
                    }

                    override fun onServicesDiscovered(
                        gatt: BluetoothGatt,
                        status: Int,
                    ) {
                        if (!servicesOk.isCompleted) {
                            servicesOk.complete(
                                status == BluetoothGatt.GATT_SUCCESS &&
                                    gatt.getService(HEART_RATE_SERVICE) != null,
                            )
                        }
                    }

                    override fun onCharacteristicChanged(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                    ) {
                        if (characteristic.uuid != HEART_RATE_MEASUREMENT) return
                        val data = characteristic.value ?: return
                        val now = System.currentTimeMillis()
                        val elapsed = android.os.SystemClock.elapsedRealtime()
                        val (results, _) = parseHeartRateFrames(data, 0, elapsed, now)
                        for (result in results) {
                            if (result is ParseResult.Complete) {
                                result.heartRate?.let { onSample?.invoke(it) }
                                if (recordRrIntervals) {
                                    for (rr in result.rrIntervals) {
                                        onRrInterval?.invoke(rr)
                                    }
                                }
                            }
                        }
                    }
                }
            val gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            synchronized(lock) { currentGatt = gatt }
            if (!connected.await()) {
                if (++attempt >= RECONNECT_BACKOFF_MS.size) return@connectAndStream
                delay(RECONNECT_BACKOFF_MS[attempt - 1])
                continue
            }
            if (!servicesOk.await()) {
                if (++attempt >= RECONNECT_BACKOFF_MS.size) return@connectAndStream
                delay(RECONNECT_BACKOFF_MS[attempt - 1])
                continue
            }
            if (!enableNotifications(gatt)) {
                if (++attempt >= RECONNECT_BACKOFF_MS.size) return@connectAndStream
                delay(RECONNECT_BACKOFF_MS[attempt - 1])
                continue
            }
            // Streaming: wait until cancelled (stop) or the connection drops.
            disconnected.await()
            // Connection lost: reconnect with backoff.
            if (++attempt >= RECONNECT_BACKOFF_MS.size) return@connectAndStream
            delay(RECONNECT_BACKOFF_MS[attempt - 1])
        }
    }

    private fun enableNotifications(gatt: BluetoothGatt): Boolean {
        val service = gatt.getService(HEART_RATE_SERVICE) ?: return false
        val characteristic = service.getCharacteristic(HEART_RATE_MEASUREMENT) ?: return false
        if (!gatt.setCharacteristicNotification(characteristic, true)) return false
        val descriptor = characteristic.getDescriptor(CCCD) ?: return false
        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        return gatt.writeDescriptor(descriptor)
    }
}
