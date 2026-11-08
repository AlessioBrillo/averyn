import UIKit

/// The battery level, read on the main thread and kept for whichever thread asks for it.
///
/// The recorder asks for the level every time it logs a state event, and it does so from background queues;
/// `UIDevice` is main-thread API. This class is the only reader of it: it keeps the last level and refreshes it when the
/// system says it changed.
final class BatteryCache: @unchecked Sendable {
    private let lock = NSLock()
    private var level: Int32?

    /// Percent, 0...100; nil when the system does not report one (a simulator, monitoring off).
    var percent: Int32? {
        lock.lock()
        defer { lock.unlock() }
        return level
    }

    @MainActor
    init() {
        UIDevice.current.isBatteryMonitoringEnabled = true
        refresh()
        NotificationCenter.default.addObserver(
            forName: UIDevice.batteryLevelDidChangeNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in self?.refresh() }
        }
    }

    @MainActor
    private func refresh() {
        let raw = UIDevice.current.batteryLevel
        lock.lock()
        level = raw >= 0 ? Int32(raw * 100) : nil
        lock.unlock()
    }
}
