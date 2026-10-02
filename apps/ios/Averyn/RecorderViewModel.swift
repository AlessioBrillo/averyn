import Foundation
import Shared
import UIKit

@MainActor
final class RecorderViewModel: ObservableObject {
    let store: ActivityStore
    let recorder: ActivityRecorder
    private lazy var tracker = LocationTracker(recorder: recorder)

    @Published var snapshot: LiveSnapshot
    @Published var pendingRecovery: ActivityMetadata?
    @Published var exportURL: URL?
    @Published var diagnostics: DiagnosticsReport?
    @Published var diagnosticsURL: URL?
    /// F5: a recovery that lost store lines is surfaced, never silent — see RecoveryResult.droppedRecordCount.
    @Published var recoveryWarning: String?

    init() {
        let store = AppModel.shared.store
        self.store = store
        let recorder = ActivityRecorder(store: store)
        self.recorder = recorder
        self.snapshot = recorder.snapshot()
        // TDD-0001 F4: an interrupted activity is offered for resume/finish once, right at launch.
        self.pendingRecovery = store.interrupted().first

        UIDevice.current.isBatteryMonitoringEnabled = true
        recorder.batteryPercent = {
            let level = UIDevice.current.batteryLevel
            return level >= 0 ? KotlinInt(int: Int32(level * 100)) : nil
        }

        recorder.listener = { [weak self] newSnapshot in
            DispatchQueue.main.async { self?.snapshot = newSnapshot }
        }
    }

    func start(sport: Sport) {
        let id = UUID().uuidString
        recorder.start(activityId: id, sport: sport, elapsedRealtimeMs: nowElapsedRealtimeMs(), timeMs: nowTimeMs())
        tracker.start()
    }

    func pause() {
        recorder.pause(elapsedRealtimeMs: nowElapsedRealtimeMs(), timeMs: nowTimeMs(), reason: .user)
    }

    func resume() {
        recorder.resume(elapsedRealtimeMs: nowElapsedRealtimeMs(), timeMs: nowTimeMs())
    }

    func stop() {
        let completed = recorder.stop(elapsedRealtimeMs: nowElapsedRealtimeMs(), timeMs: nowTimeMs())
        tracker.stop()
        export(activityId: completed.activityId, diagnostics: completed.diagnostics)
    }

    func resolveRecoveryResume() {
        guard let metadata = pendingRecovery else { return }
        let result = recorder.recover(activityId: metadata.activityId, nowElapsedRealtimeMs: nowElapsedRealtimeMs(), nowTimeMs: nowTimeMs())
        if let resumed = result as? RecoveryResult.Resumed {
            snapshot = resumed.snapshot
            recoveryWarning = droppedRecordWarning(resumed.droppedRecordCount)
            tracker.start()
        } else if let completed = result as? RecoveryResult.Completed {
            recoveryWarning = droppedRecordWarning(completed.droppedRecordCount)
            export(activityId: completed.activity.activityId, diagnostics: completed.activity.diagnostics)
        }
        pendingRecovery = nil
    }

    func resolveRecoveryFinish() {
        guard let metadata = pendingRecovery else { return }
        let result = recorder.recover(activityId: metadata.activityId, nowElapsedRealtimeMs: nowElapsedRealtimeMs(), nowTimeMs: nowTimeMs())
        if result is RecoveryResult.Resumed {
            // The last known activity time, not "now": the app can be reopened long after the kill, and
            // stopping at wall-clock now would count that whole gap as moving time.
            let completed = recorder.stop(
                elapsedRealtimeMs: recorder.lastKnownElapsedRealtimeMs,
                timeMs: recorder.lastKnownTimeMs
            )
            if let resumed = result as? RecoveryResult.Resumed {
                recoveryWarning = droppedRecordWarning(resumed.droppedRecordCount)
            }
            export(activityId: completed.activityId, diagnostics: completed.diagnostics)
        } else if let completed = result as? RecoveryResult.Completed {
            recoveryWarning = droppedRecordWarning(completed.droppedRecordCount)
            export(activityId: completed.activity.activityId, diagnostics: completed.activity.diagnostics)
        }
        pendingRecovery = nil
    }

    /// Called whenever an activity ends (stop, or finishing a recovered one): export it and queue its upload.
    private func export(activityId: String, diagnostics: DiagnosticsReport) {
        Task { await AppModel.shared.sync.syncNow() }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(activityId).gpx")
        let store = self.store
        // Streams and re-serializes every stored sample: real I/O, kept off the main thread.
        DispatchQueue.global(qos: .utility).async {
            store.exportGpxToFile(activityId: activityId, filePath: url.path, content: .raw)
            DispatchQueue.main.async { [weak self] in
                self?.exportURL = url
            }
        }
        self.diagnostics = diagnostics
        writeDiagnosticsJson(activityId: activityId, diagnostics: diagnostics)
    }

    private func writeDiagnosticsJson(activityId: String, diagnostics: DiagnosticsReport) {
        let device = DeviceInfo(
            model: UIDevice.current.model,
            osVersion: "iOS \(UIDevice.current.systemVersion)",
            appVersion: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "unknown"
        )
        let json = diagnostics.toJson(device: device)
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(activityId)-diagnostics.json")
        try? json.write(to: url, atomically: true, encoding: .utf8)
        diagnosticsURL = url
    }
}

private func droppedRecordWarning(_ droppedRecordCount: Int32) -> String? {
    droppedRecordCount > 0 ? "Recovery lost \(droppedRecordCount) corrupted record(s) from the interrupted activity" : nil
}

private func nowElapsedRealtimeMs() -> Int64 {
    Int64(ProcessInfo.processInfo.systemUptime * 1000)
}

private func nowTimeMs() -> Int64 {
    Int64(Date().timeIntervalSince1970 * 1000)
}
