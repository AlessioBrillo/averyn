import CoreBluetooth
import Foundation
@preconcurrency import Shared

/// What `AppModel` needs of a heart-rate source: the shared adapter plus the count of values the model refused
/// and the late search for a sensor that was switched on after the start. The strap and the band both are one.
protocol HeartRateSource: AnyObject, Sendable, SensorAdapter {
    var onInvalid: (() -> Void)? { get set }
    func searchAgainIfNeverFound()
}

extension HeartRateMonitor: HeartRateSource {}

/// What the user chose about a SYNAPSE band, kept on this device only (UserDefaults; the settings screen reads the
/// same keys through `@AppStorage`).
enum BandPrefs {
    static let useKey = "band.use"
    static let rawOnMobileKey = "band.rawOnMobile"
    static let identifierKey = "band.identifier"

    static var useBand: Bool { UserDefaults.standard.bool(forKey: useKey) }
    static var rawOnMobile: Bool { UserDefaults.standard.bool(forKey: rawOnMobileKey) }
}

/// The SYNAPSE band over BLE (ADR-0030), the iOS half; the same flow as the Android `SynapseBleAdapter`.
/// One connection does three things:
///
/// - the standard heart-rate service (`0x180D`) feeds the recording exactly as `HeartRateMonitor` does for a strap;
/// - the consumer service's feature and status notifications and, under `raw_signals`, the research service's raw
///   streams go to a shared `SynapseRecorder`, which writes the two sidecars of the activity under the user's
///   consents (masking before the disk);
/// - on every connection the band gets its wall clock (`set_time`) and the raw streams are started or stopped to
///   match the consent (a new connection starts them running).
///
/// iOS negotiates the MTU by itself and queues the GATT operations in order, so there is no MTU request and no
/// stepper here. All BLE state lives on `queue`; the heart-rate callbacks are handed to the main queue, where
/// `HeartRateMonitor` delivers them too.
///
/// UUIDs are those of `contracts/gatt_v2.json` (`SynapseContract.kt` in `shared/sensors`; a test there compares
/// the Kotlin constants with the contract). They are written out here because the Kotlin export of top-level
/// constants was not checked: keep the two in step.
///
/// Nothing here has been compiled or run against a band: the file was written without a Mac.
final class SynapseBand: NSObject, @unchecked Sendable, CBCentralManagerDelegate, CBPeripheralDelegate {
    // MARK: - HeartRateSource / SensorAdapter

    var isAvailable: Bool { CBManager.authorization == .allowedAlways }

    var supportsRr: Bool { true }

    /// Set from the cached consent (fail-closed: default false). Read on every frame.
    var recordRrIntervals: Bool {
        get { lock.withLock { _recordRr } }
        set { lock.withLock { _recordRr = newValue } }
    }

    var onSample: ((HeartRateSample) -> Void)? = nil
    var onRrInterval: ((RrInterval) -> Void)? = nil
    var onInvalid: (() -> Void)? = nil

    /// The consents in force. The recorder reads them before every write; a raw consent withdrawn mid-recording also
    /// stops the band's raw streams. One granted mid-recording takes effect on the next connection.
    var consents: SynapseConsents {
        get { lock.withLock { _consents } }
        set {
            let change: (revoked: Bool, granted: Bool) = lock.withLock {
                let before = _consents.rawSignals
                _consents = newValue
                return (before && !newValue.rawSignals, !before && newValue.rawSignals)
            }
            queue.async {
                // `consents` of the recorder is volatile: safe to set from here while the write queue reads it.
                self.recorder?.consents = newValue
                if change.revoked { self.writeControl(SynapseControl.shared.stop()) }
                // Granted while connected (or the first answer of the server came after the connection): the raw
                // characteristics are subscribed and the streams started now, not at the next connection.
                if change.granted, let peripheral = self.peripheral, self.controlPoint != nil {
                    self.subscribe(peripheral, to: Set(self.rawUUIDs))
                    self.writeControl(SynapseControl.shared.start())
                }
            }
        }
    }

    // MARK: - State

    private let store: ActivityStore
    private let queue = DispatchQueue(label: "dev.averyn.band")
    /// The sidecar files are appended here, one record at a time, so that disk I/O on the raw streams never holds up the
    /// BLE callbacks (and the heart rate of the recording). The recorder is touched on this queue only.
    private let writeQueue = DispatchQueue(label: "dev.averyn.band.write")
    private let lock = NSLock()
    private var _recordRr = false
    private var _consents = SynapseConsents.companion.NONE

    // Below: `queue` only.
    private var central: CBCentralManager?
    private var peripheral: CBPeripheral?
    private var controlPoint: CBCharacteristic?
    private var recorder: SynapseRecorder?
    private var sessionId = ""
    private var startUptimeNanos: UInt64 = 0
    private var running = false
    private var scanning = false
    private var attempt = 0
    private var pendingServices = 0
    private var scanTimeout: DispatchWorkItem?
    private var retryWork: DispatchWorkItem?

    private static let scanTimeoutSeconds: TimeInterval = 15
    private static let reconnectBackoffSeconds: [TimeInterval] = [1, 2, 4, 8, 16, 30]

    private let consumerService = CBUUID(string: "f9e08ffd-3d34-441f-97a6-1bf0958123f1")
    private let featureUUID = CBUUID(string: "23443aaf-382c-4475-baf8-ea3c59ba1a82")
    private let statusUUID = CBUUID(string: "fea798da-b114-47c9-8ec4-265bb358e741")
    private let controlUUID = CBUUID(string: "bc560345-f1e9-4bbf-98a7-e545c88c02a1")
    private let researchService = CBUUID(string: "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c70")
    private let rawUUIDs = [
        "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c71", // ECG
        "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c72", // PPG
        "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c73", // ACC
        "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c74", // GYRO
        "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c75", // MAG
        "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c79", // POWER
    ].map { CBUUID(string: $0) }
    private let hrService = CBUUID(string: "180D")
    private let hrMeasurement = CBUUID(string: "2A37")

    init(store: ActivityStore) {
        self.store = store
        super.init()
        // The system hands a restored connection back only to a manager created at launch with the same
        // identifier (as `HeartRateMonitor` does); not for someone who never chose the band.
        if BandPrefs.useBand { ensureCentral() }
    }

    private func ensureCentral() {
        guard central == nil else { return }
        central = CBCentralManager(
            delegate: self,
            queue: queue,
            options: [CBCentralManagerOptionRestoreIdentifierKey: "dev.averyn.app.band"]
        )
    }

    // MARK: - SensorAdapter

    func start(sessionId: String) async throws {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            queue.async {
                self.begin(sessionId: sessionId)
                continuation.resume()
            }
        }
    }

    func stop() async throws {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            queue.async {
                self.end()
                continuation.resume()
            }
        }
    }

    /// A band switched on after the start: scans again when none was ever found (a scan costs battery, so it is only
    /// asked for now and then, from outside). A band that was connected before has its own reconnect.
    func searchAgainIfNeverFound() {
        queue.async {
            guard self.running, self.knownIdentifier == nil, self.peripheral == nil else { return }
            self.connectOrScan()
        }
    }

    // MARK: - Connection (queue)

    private var knownIdentifier: UUID? {
        UserDefaults.standard.string(forKey: BandPrefs.identifierKey).flatMap { UUID(uuidString: $0) }
    }

    private func begin(sessionId: String) {
        guard !sessionId.isEmpty else { return }
        if recorder == nil || sessionId != self.sessionId {
            self.sessionId = sessionId
            // t_rx_ns counts from the start of the recording, also after a restart of the process in the middle of
            // it: the sidecar is appended to, and its clock must not jump back to zero.
            let sinceStartMs = store.metadataOf(activityId: sessionId).map { nowTimeMs() - $0.startedAtMs } ?? 0
            startUptimeNanos = clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW) &- UInt64(max(sinceStartMs, 0)) &* 1_000_000
            recorder = SynapseRecorder(
                features: store.synapseSink(activityId: sessionId, kind: .features),
                raw: store.synapseSink(activityId: sessionId, kind: .raw),
                consents: consents
            )
        }
        guard !running else { return }
        running = true
        attempt = 0
        ensureCentral()
        connectOrScan()
    }

    private func end() {
        running = false
        retryWork?.cancel()
        retryWork = nil
        stopScanning()
        if let current = peripheral {
            current.delegate = nil
            central?.cancelPeripheralConnection(current)
        }
        peripheral = nil
        controlPoint = nil
        recorder = nil
        sessionId = ""
    }

    private func connectOrScan() {
        guard running, !scanning, let central, central.state == .poweredOn else { return }
        if let id = knownIdentifier, let known = central.retrievePeripherals(withIdentifiers: [id]).first {
            connect(known)
            return
        }
        scanning = true
        central.scanForPeripherals(withServices: [consumerService], options: nil)
        let timeout = DispatchWorkItem { [weak self] in self?.scanTimedOut() }
        scanTimeout = timeout
        queue.asyncAfter(deadline: .now() + Self.scanTimeoutSeconds, execute: timeout)
    }

    /// No band in range: it is optional, so recording goes on without it. One that was connected before is waited
    /// for; without one, no more scans.
    private func scanTimedOut() {
        stopScanning()
        if knownIdentifier != nil { scheduleReconnect() }
    }

    private func stopScanning() {
        scanTimeout?.cancel()
        scanTimeout = nil
        if scanning {
            central?.stopScan()
            scanning = false
        }
    }

    private func connect(_ target: CBPeripheral) {
        peripheral = target
        target.delegate = self
        central?.connect(target, options: nil)
    }

    private func scheduleReconnect() {
        peripheral = nil
        controlPoint = nil
        guard running else { return }
        retryWork?.cancel()
        let delay = Self.reconnectBackoffSeconds[min(attempt, Self.reconnectBackoffSeconds.count - 1)]
        attempt += 1
        let work = DispatchWorkItem { [weak self] in self?.connectOrScan() }
        retryWork = work
        queue.asyncAfter(deadline: .now() + delay, execute: work)
    }

    // MARK: - CBCentralManagerDelegate (queue)

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn, running, peripheral == nil { connectOrScan() }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        guard running else { return }
        stopScanning()
        connect(peripheral)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        attempt = 0
        UserDefaults.standard.set(peripheral.identifier.uuidString, forKey: BandPrefs.identifierKey)
        peripheral.delegate = self
        pendingServices = 0
        peripheral.discoverServices([consumerService, researchService, hrService])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        scheduleReconnect()
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral,
                        error: Error?) {
        scheduleReconnect()
    }

    /// State restoration: the system relaunched the app and hands back the band it kept connected.
    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        guard let restored = (dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral])?.first else {
            return
        }
        peripheral = restored
        UserDefaults.standard.set(restored.identifier.uuidString, forKey: BandPrefs.identifierKey)
    }

    // MARK: - CBPeripheralDelegate (queue)

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        let services = peripheral.services ?? []
        pendingServices = services.count
        // Without the consumer service this is not (yet) a band: disconnect, so the reconnect with backoff tries
        // again instead of holding a silent connection.
        guard error == nil, services.contains(where: { $0.uuid == consumerService }) else {
            central?.cancelPeripheralConnection(peripheral)
            return
        }
        for service in services { peripheral.discoverCharacteristics(nil, for: service) }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService,
                    error: Error?) {
        pendingServices -= 1
        if pendingServices <= 0 { setUp(peripheral) }
    }

    /// Subscribes, then gives the band its clock and starts or stops the raw streams. CoreBluetooth runs the
    /// operations in the order they are issued.
    private func setUp(_ peripheral: CBPeripheral) {
        let rawAllowed = consents.rawSignals
        var wanted: Set<CBUUID> = [featureUUID, statusUUID, hrMeasurement]
        if rawAllowed { wanted.formUnion(rawUUIDs) }
        for service in peripheral.services ?? [] {
            for characteristic in service.characteristics ?? [] where characteristic.uuid == controlUUID {
                controlPoint = characteristic
            }
        }
        subscribe(peripheral, to: wanted)
        writeControl(SynapseControl.shared.setTime(epochMillis: nowTimeMs()))
        writeControl(rawAllowed ? SynapseControl.shared.start() : SynapseControl.shared.stop())
    }

    private func subscribe(_ peripheral: CBPeripheral, to wanted: Set<CBUUID>) {
        for service in peripheral.services ?? [] {
            for characteristic in service.characteristics ?? [] where wanted.contains(characteristic.uuid) {
                peripheral.setNotifyValue(true, for: characteristic)
            }
        }
    }

    private func writeControl(_ bytes: KotlinByteArray) {
        guard let peripheral, let controlPoint else { return }
        var data = Data(count: Int(bytes.size))
        for index in 0 ..< Int(bytes.size) { data[index] = UInt8(bitPattern: bytes.get(index: Int32(index))) }
        peripheral.writeValue(data, for: controlPoint, type: .withResponse)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic,
                    error: Error?) {
        guard let data = characteristic.value else { return }
        let bytes = KotlinByteArray(size: Int32(data.count))
        for (index, byte) in data.enumerated() { bytes.set(index: Int32(index), value: Int8(bitPattern: byte)) }

        if characteristic.uuid == hrMeasurement {
            deliverHeartRate(bytes)
        } else {
            let sinceStart = Int64(clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW) &- startUptimeNanos)
            let uuid = characteristic.uuid.uuidString
            guard let recorder else { return }
            nonisolated(unsafe) let frame = bytes // handed over once, not touched here again
            nonisolated(unsafe) let target = recorder
            writeQueue.async { _ = target.onNotify(characteristicUuid: uuid, data: frame, receivedNanos: sinceStart) }
        }
    }

    private func deliverHeartRate(_ bytes: KotlinByteArray) {
        let parsed = HeartRateFrameParserKt.parseHeartRateFrames(
            data: bytes, offset: 0, elapsedRealtimeMs: nowElapsedRealtimeMs(), timeMs: nowTimeMs()
        )
        // Kotlin values handed to the main queue once, never touched here again.
        nonisolated(unsafe) let results = (parsed.first as? [Any]) ?? []
        let rr = recordRrIntervals
        DispatchQueue.main.async {
            for case is ParseResult.Invalid in results { self.onInvalid?() }
            for case let complete as ParseResult.Complete in results {
                if let sample = complete.heartRate { self.onSample?(sample) }
                if rr { for interval in complete.rrIntervals { self.onRrInterval?(interval) } }
            }
        }
    }
}

extension SynapseBand: HeartRateSource {}
