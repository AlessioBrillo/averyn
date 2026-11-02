import CoreBluetooth
import Foundation
import Shared

/// iOS adapter over the standard BLE Heart Rate service (`0x180D` / `0x2A37`).
///
/// Implements `SensorAdapter`: parses `0x2A37` frames with the shared `parseHeartRateFrames` and hands
/// `HeartRateSample`/`RrInterval` to the caller through callbacks. The connection is best effort: a missing
/// strap never stops GPS tracking. No vendor-specific code (ADR-0014).
///
/// Every piece of mutable state is touched on the main queue only: the central manager is created on it, so the
/// delegate callbacks arrive on it, and `start`/`stop` hop to it. That is why the class can say it is `Sendable`
/// without a lock.
final class HeartRateMonitor: NSObject, @unchecked Sendable, SensorAdapter, CBCentralManagerDelegate,
    CBPeripheralDelegate {
    // MARK: - SensorAdapter

    var isAvailable: Bool {
        central.state == .poweredOn && CBManager.authorization == .allowedAlways
    }

    var supportsRr: Bool { true }

    /// Set by the caller from the cached consent before `start` (fail-closed: default false). Read on every
    /// frame: a revocation mid-activity stops the RR intervals without stopping the bpm (ADR-0021).
    var recordRrIntervals: Bool = false

    var onSample: ((HeartRateSample) -> Void)? = nil
    var onRrInterval: ((RrInterval) -> Void)? = nil
    /// A value of a frame the heart-rate model rejects: the caller counts it as a dropped input.
    var onInvalid: (() -> Void)? = nil

    // MARK: - State (main queue only)

    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var running = false
    private var scanning = false
    private var attempt = 0
    private var lastIdentifier: UUID?
    private var scanTimeout: DispatchWorkItem?
    private var retryWork: DispatchWorkItem?

    private static let scanTimeoutSeconds: TimeInterval = 15
    /// Reconnect backoff: 1 s, 2 s, 4 s, 8 s, then stop.
    /// The last value repeats: a strap that drops often during a long activity is retried for as long as it lasts.
    private static let reconnectBackoffSeconds: [TimeInterval] = [1, 2, 4, 8, 16, 30]

    private let hrServiceUUID = CBUUID(string: "180D")
    private let hrMeasurementUUID = CBUUID(string: "2A37")

    override init() {
        super.init()
        central = CBCentralManager(
            delegate: self,
            queue: nil,
            options: [CBCentralManagerOptionRestoreIdentifierKey: "dev.averyn.app.hr"]
        )
    }

    // MARK: - SensorAdapter

    func start(sessionId: String) async throws {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            DispatchQueue.main.async {
                self.begin()
                continuation.resume()
            }
        }
    }

    func stop() async throws {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            DispatchQueue.main.async {
                self.end()
                continuation.resume()
            }
        }
    }

    // MARK: - Connection

    private func begin() {
        guard !running else { return }
        running = true
        attempt = 0
        connectOrScan()
    }

    private func end() {
        running = false
        retryWork?.cancel()
        retryWork = nil
        stopScanning()
        if let current = peripheral {
            current.delegate = nil
            central.cancelPeripheralConnection(current)
        }
        peripheral = nil
    }

    private func connectOrScan() {
        guard running, !scanning, central.state == .poweredOn else { return }

        // Prefer the strap we paired with last.
        if let id = lastIdentifier, let known = central.retrievePeripherals(withIdentifiers: [id]).first {
            connect(known)
            return
        }

        scanning = true
        central.scanForPeripherals(withServices: [hrServiceUUID], options: nil)
        let timeout = DispatchWorkItem { [weak self] in self?.scanTimedOut() }
        scanTimeout = timeout
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.scanTimeoutSeconds, execute: timeout)
    }

    /// No strap in range: it is optional, so recording simply goes on without it. A strap that was connected before
    /// is waited for (it was switched off, or out of range for a moment); without one, no more scans: they cost
    /// battery for nobody.
    private func scanTimedOut() {
        stopScanning()
        if lastIdentifier != nil { scheduleReconnect() }
    }

    private func stopScanning() {
        scanTimeout?.cancel()
        scanTimeout = nil
        if scanning {
            central.stopScan()
            scanning = false
        }
    }

    private func connect(_ target: CBPeripheral) {
        peripheral = target
        target.delegate = self
        central.connect(target, options: nil)
    }

    private func scheduleReconnect() {
        peripheral = nil
        guard running else { return }
        let delay = Self.reconnectBackoffSeconds[min(attempt, Self.reconnectBackoffSeconds.count - 1)]
        attempt += 1
        let work = DispatchWorkItem { [weak self] in self?.connectOrScan() }
        retryWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
    }

    // MARK: - CBCentralManagerDelegate

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        // The radio came on after `start` asked for a strap: look for it now.
        if central.state == .poweredOn, running, peripheral == nil {
            connectOrScan()
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        guard running else { return }
        stopScanning()
        connect(peripheral)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        attempt = 0
        lastIdentifier = peripheral.identifier
        peripheral.delegate = self
        peripheral.discoverServices([hrServiceUUID])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        scheduleReconnect()
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral,
                        error: Error?) {
        scheduleReconnect()
    }

    /// State restoration: the system relaunched the app and hands back the strap it kept connected.
    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        guard let restored = (dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral])?.first else {
            return
        }
        // Keep a reference so the connection survives until `start` reconnects to it.
        peripheral = restored
        lastIdentifier = restored.identifier
    }

    // MARK: - CBPeripheralDelegate

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let service = peripheral.services?.first(where: { $0.uuid == hrServiceUUID }) else { return }
        peripheral.discoverCharacteristics([hrMeasurementUUID], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService,
                    error: Error?) {
        guard let characteristic = service.characteristics?.first(where: { $0.uuid == hrMeasurementUUID }) else {
            return
        }
        peripheral.setNotifyValue(true, for: characteristic)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic,
                    error: Error?) {
        guard characteristic.uuid == hrMeasurementUUID, let data = characteristic.value else { return }

        let bytes = KotlinByteArray(size: Int32(data.count))
        for (index, byte) in data.enumerated() {
            bytes.set(index: Int32(index), value: Int8(bitPattern: byte))
        }
        let parsed = HeartRateFrameParserKt.parseHeartRateFrames(
            data: bytes, offset: 0, elapsedRealtimeMs: nowElapsedRealtimeMs(), timeMs: nowTimeMs()
        )

        let results = (parsed.first as? [Any]) ?? []
        for case is ParseResult.Invalid in results {
            onInvalid?()
        }
        for case let complete as ParseResult.Complete in results {
            if let sample = complete.heartRate {
                onSample?(sample)
            }
            if recordRrIntervals {
                for interval in complete.rrIntervals {
                    onRrInterval?(interval)
                }
            }
            // A `ParseResult.Incomplete` (truncated frame) is dropped: the next notification brings a whole one.
        }
    }
}
