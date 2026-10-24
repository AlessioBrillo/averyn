import CoreBluetooth
import Foundation
import Shared

/// iOS adapter over the standard BLE Heart Rate service (`0x180D` / `0x2A37`).
///
/// Implements `SensorAdapter`: parses `0x2A37` frames with the shared `parseHeartRateFrames`,
/// hands `HeartRateSample`/`RrInterval` to the caller via callbacks. The connection is
/// best-effort: a missing strap never stops GPS tracking.
///
/// Bluetooth is only used while an activity is being prepared or recorded: the central manager
/// is always alive (for state restoration) but scanning stops as soon as the activity reaches a
/// terminal state (TDD-0007 §4).
final class HeartRateMonitor: NSObject, SensorAdapter, CBCentralManagerDelegate, CBPeripheralDelegate {

    // MARK: - SensorAdapter

    var isAvailable: Bool {
        central.state == .poweredOn && CBManager.authorization == .allowedAlways
    }

    var supportsRr: Bool { true }

    /// Set by the caller from the cached consent before `start` (fail-closed: default false).
    /// Re-read on every interval: a revocation mid-activity stops RR within one refresh cycle.
    var recordRrIntervals: Bool = false

    var onSample: ((HeartRateSample) -> Void)? = nil
    var onRrInterval: ((RrInterval) -> Void)? = nil

    // MARK: - Private state

    private let central: CBCentralManager

    /// The peripheral we are connected to (or restoring).
    private var peripheral: CBPeripheral?

    /// The Heart Rate Measurement characteristic (`0x2A37`).
    private var measurementCharacteristic: CBCharacteristic?

    private let scanTimeoutMs: Int64 = 15_000
    /// Reconnect backoff: 1s, 2s, 4s, 8s, then stop.
    private let reconnectBackoffMs: [Int64] = [1_000, 2_000, 4_000, 8_000]

    /// The running connection task. Nil when not connecting/streaming.
    private var connectTask: Task<Void, Never>? = nil

    /// The running scan task (for the scan timeout).
    private var scanTask: Task<Void, Never>? = nil

    private let lock = NSLock()

    /// Last peripheral we successfully connected to, so a restart can reconnect to the same strap.
    private var lastPeripheralIdentifier: UUID? = nil

    /// Continuation resumed when `didConnect` fires.
    private var connectContinuation: ((Bool) -> Void)? = nil

    /// Continuation resumed when `didDiscoverServices` fires.
    private var servicesContinuation: ((Bool) -> Void)? = nil

    /// Continuation resumed when notifications are enabled.
    private var notifyContinuation: ((Bool) -> Void)? = nil

    /// Continuation resumed when the scan finds a device (or times out).
    private var scanContinuation: ((CBPeripheral?) -> Void)? = nil

    /// Standard Bluetooth Heart Rate service.
    private let hrServiceUUID = CBUUID(string: "0000180d-0000-1000-8000-00805f9b34fb")

    /// Heart Rate Measurement characteristic.
    private let hrMeasurementUUID = CBUUID(string: "00002a37-0000-1000-8000-00805f9b34fb")

    override init() {
        central = CBCentralManager(
            delegate: nil,
            queue: nil,
            options: [CBCentralManagerOptionRestoreIdentifierKey: "dev.averyn.app.hr"]
        )
        super.init()
        central.delegate = self
    }

    deinit {
        central.delegate = nil
        connectTask?.cancel()
        connectTask = nil
        scanTask?.cancel()
        scanTask = nil
        stopScanning()
        lock.lock()
        if let p = peripheral {
            central.cancelPeripheralConnection(p)
        }
        peripheral = nil
        measurementCharacteristic = nil
        lock.unlock()
    }

    // MARK: - SensorAdapter

    func start(sessionId: String) async throws {
        // Cancel any existing connection job: only one session at a time.
        connectTask?.cancel()
        connectTask = nil

        connectTask = Task { [weak self] in
            await self?.runConnectionLoop(sessionId: sessionId)
        }
    }

    func stop() async throws {
        connectTask?.cancel()
        connectTask = nil
        scanTask?.cancel()
        scanTask = nil

        lock.lock()
        peripheral?.delegate = nil
        if let p = peripheral {
            central.cancelPeripheralConnection(p)
        }
        peripheral = nil
        measurementCharacteristic = nil
        lock.unlock()

        stopScanning()
    }

    // MARK: - Connection loop

    private func runConnectionLoop(sessionId: String) async {
        var attempt = 0

        while !Task.isCancelled {
            guard let device = await findHeartRateDevice() else {
                // No sensor found: the strap is optional, recording continues without it.
                return
            }

            lastPeripheralIdentifier = device.identifier

            let streamed = await connectAndStream(device: device)

            if Task.isCancelled { return }

            if !streamed {
                // Connection failed: reconnect with backoff.
                if attempt >= reconnectBackoffMs.count { return }
                try? await Task.sleep(nanoseconds: reconnectBackoffMs[attempt] * 1_000_000)
                attempt += 1
                continue
            }

            // Connection lost normally: reconnect with backoff.
            if attempt >= reconnectBackoffMs.count { return }
            try? await Task.sleep(nanoseconds: reconnectBackoffMs[attempt] * 1_000_000)
            attempt += 1
        }
    }

    // MARK: - Discovery

    private func findHeartRateDevice() async -> CBPeripheral? {
        // Prefer reconnecting to the last strap we successfully paired with.
        if let id = lastPeripheralIdentifier {
            if let p = central.retrievePeripherals(withIdentifiers: [id]).first {
                return p
            }
        }

        return await withCheckedContinuation { continuation in
            scanContinuation = continuation

            // Timeout: stop scanning and report no device found.
            scanTask = Task { [weak self] in
                try? await Task.sleep(nanoseconds: scanTimeoutMs * 1_000_000)
                guard !Task.isCancelled else { return }
                self?.stopScanning()
                continuation.resume(returning: nil)
            }

            central.scanForPeripherals(withServices: [hrServiceUUID], options: nil)
        }
    }

    private func stopScanning() {
        central.stopScan()
        scanTask?.cancel()
        scanTask = nil
        scanContinuation = nil
    }

    private func connectAndStream(device: CBPeripheral) async -> Bool {
        // Connect.
        let connected = await withCheckedContinuation { continuation in
            connectContinuation = continuation
            central.connect(device, options: nil)
        }

        if !connected || Task.isCancelled { return false }

        // Discover services and enable notifications.
        let ok = await withCheckedContinuation { continuation in
            servicesContinuation = continuation
            notifyContinuation = nil
            peripheral?.delegate = self
            peripheral?.discoverServices([hrServiceUUID])
        }

        if !ok || Task.isCancelled { return false }

        // Wait for notifications to be enabled (delegate will resume `notifyContinuation`).
        let notified = await withCheckedContinuation { continuation in
            notifyContinuation = continuation
            // Timeout: give up after 10 seconds waiting for notification setup.
            DispatchQueue.global().asyncAfter(deadline: .now() + 10) {
                if !Task.isCancelled {
                    continuation.resume(returning: false)
                }
            }
        }

        return notified
    }

    // MARK: - CBCentralManagerDelegate

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        // State changes are observed through `isAvailable`; nothing to do here.
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        guard let services = advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID],
              services.contains(hrServiceUUID) else { return }

        stopScanning()
        scanContinuation?(peripheral)
        scanContinuation = nil
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        lock.lock()
        self.peripheral = peripheral
        lock.unlock()

        peripheral.delegate = self
        peripheral.discoverServices([hrServiceUUID])
        connectContinuation?(true)
        connectContinuation = nil
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        connectContinuation?(false)
        connectContinuation = nil
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        lock.lock()
        if self.peripheral === peripheral {
            self.peripheral = nil
            self.measurementCharacteristic = nil
        }
        lock.unlock()
    }

    // State restoration: the system relaunched the app and gives us back our peripherals.
    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        if let peripherals = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral] {
            lock.lock()
            self.peripheral = peripherals.first
            self.peripheral?.delegate = self
            lock.unlock()
        }
    }

    // MARK: - CBPeripheralDelegate

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let services = peripheral.services else {
            servicesContinuation?(false)
            servicesContinuation = nil
            return
        }
        for service in services where service.uuid == hrServiceUUID {
            peripheral.discoverCharacteristics([hrMeasurementUUID], for: service)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService,
                    error: Error?) {
        guard service.uuid == hrServiceUUID,
              let characteristics = service.characteristics else {
            servicesContinuation?(false)
            servicesContinuation = nil
            return
        }

        var found = false
        for characteristic in characteristics where characteristic.uuid == hrMeasurementUUID {
            lock.lock()
            measurementCharacteristic = characteristic
            lock.unlock()
            peripheral.setNotifyValue(true, for: characteristic)
            found = true
        }

        if found {
            // `didUpdateNotificationStateFor` will resume `notifyContinuation`.
        } else {
            servicesContinuation?(false)
            servicesContinuation = nil
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic,
                    error: Error?) {
        guard characteristic.uuid == hrMeasurementUUID,
              let data = characteristic.value else { return }

        let now = nowTimeMs()
        let elapsed = nowElapsedRealtimeMs()

        let (results, _) = HeartRateFrameParserKt.parseHeartRateFrames(data: data, offset: 0,
                                                                       elapsedRealtimeMs: elapsed, timeMs: now)

        for result in results {
            switch result {
            case .complete(let heartRate, let rrIntervals, _, _):
                if let heartRate = heartRate {
                    onSample?(heartRate)
                }
                if recordRrIntervals {
                    for rr in rrIntervals {
                        onRrInterval?(rr)
                    }
                }
            case .incomplete:
                // Truncated frame: the parser consumed nothing, retry with more data next notification.
                break
            }
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic,
                    error: Error?) {
        guard characteristic.uuid == hrMeasurementUUID else { return }
        let ok = error == nil && characteristic.isNotifying
        notifyContinuation?(ok)
        notifyContinuation = nil
    }
}
