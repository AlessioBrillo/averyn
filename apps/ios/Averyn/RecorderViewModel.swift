import Foundation
import Shared

@MainActor
final class RecorderViewModel: ObservableObject {
    let store: ActivityStore
    let recorder: ActivityRecorder
    private lazy var tracker = LocationTracker(recorder: recorder)

    @Published var snapshot: LiveSnapshot
    @Published var pendingRecovery: ActivityMetadata?
    @Published var exportURL: URL?

    init() {
        let dir = FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("activities", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = ActivityStore.companion.open(directoryPath: dir.path)
        self.store = store
        let recorder = ActivityRecorder(store: store)
        self.recorder = recorder
        self.snapshot = recorder.snapshot()
        // TDD-0001 F4: an interrupted activity is offered for resume/finish once, right at launch.
        self.pendingRecovery = store.interrupted().first

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
        export(activityId: completed.activityId)
    }

    func resolveRecoveryResume() {
        guard let metadata = pendingRecovery else { return }
        let result = recorder.recover(activityId: metadata.activityId, nowElapsedRealtimeMs: nowElapsedRealtimeMs(), nowTimeMs: nowTimeMs())
        if let resumed = result as? RecoveryResult.Resumed {
            snapshot = resumed.snapshot
            tracker.start()
        } else if let completed = result as? RecoveryResult.Completed {
            export(activityId: completed.activity.activityId)
        }
        pendingRecovery = nil
    }

    func resolveRecoveryFinish() {
        guard let metadata = pendingRecovery else { return }
        let result = recorder.recover(activityId: metadata.activityId, nowElapsedRealtimeMs: nowElapsedRealtimeMs(), nowTimeMs: nowTimeMs())
        if result is RecoveryResult.Resumed {
            let completed = recorder.stop(elapsedRealtimeMs: nowElapsedRealtimeMs(), timeMs: nowTimeMs())
            export(activityId: completed.activityId)
        } else if let completed = result as? RecoveryResult.Completed {
            export(activityId: completed.activity.activityId)
        }
        pendingRecovery = nil
    }

    private func export(activityId: String) {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(activityId).gpx")
        store.exportGpxToFile(activityId: activityId, filePath: url.path, content: .raw)
        exportURL = url
    }
}

private func nowElapsedRealtimeMs() -> Int64 {
    Int64(ProcessInfo.processInfo.systemUptime * 1000)
}

private func nowTimeMs() -> Int64 {
    Int64(Date().timeIntervalSince1970 * 1000)
}
