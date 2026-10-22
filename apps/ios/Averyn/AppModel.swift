import Foundation
import Shared
import UIKit

/// The few long-lived objects the UI and the background tasks share. The recorder and the location tracker live
/// here, not in a view model: when iOS relaunches a terminated app to keep recording (significant-change
/// location event), no scene exists, and the recording must still be continued.
@MainActor
final class AppModel {
    static let shared = AppModel()

    let store: ActivityStore
    let recorder: ActivityRecorder
    let tracker: LocationTracker
    let auth: AuthManager
    let sync: SyncService
    /// Where the activity files live; the raw file of an activity is `<id>.jsonl` in here (ADR-0015).
    let activitiesDirectory: URL
    /// Where the copy of the server lives (TDD-0011): its own directory, excluded from the backup.
    let serverCacheDirectory: URL

    /// The history of the account: the server, the copy and the device. Rebuilt when the server changes.
    private var historyRepository: HistoryRepository?
    private var historyServerURL: String?

    /// True when this launch resumed an interrupted activity by itself; the UI says so once.
    private(set) var resumedAtLaunch = false

    private init() {
        let dir = FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("activities", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        activitiesDirectory = dir
        store = ActivityStore.companion.open(directoryPath: dir.path)

        // The copy of the server holds the coordinates of the activities of the account: it is not in the
        // backup of the device (TDD-0011).
        let cacheDir = FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("server-cache", isDirectory: true)
        try? FileManager.default.createDirectory(at: cacheDir, withIntermediateDirectories: true)
        var cacheValues = URLResourceValues()
        cacheValues.isExcludedFromBackup = true
        try? cacheDir.setResourceValues(cacheValues)
        serverCacheDirectory = cacheDir

        UIDevice.current.isBatteryMonitoringEnabled = true
        let recorder = ActivityRecorder(store: store)
        recorder.batteryPercent = {
            let level = UIDevice.current.batteryLevel
            return level >= 0 ? KotlinInt(int: Int32(level * 100)) : nil
        }
        self.recorder = recorder
        tracker = LocationTracker(recorder: recorder)
        auth = AuthManager()
        sync = SyncService(store: store, auth: auth)
    }

    /// The history of the account (TDD-0011): the server, the copy on this device and the local files.
    func history() -> HistoryRepository {
        let server = auth.normalizedServerURL ?? ""
        if let current = historyRepository, historyServerURL == server { return current }
        let created = HistoryRepository.companion.create(
            store: store,
            cacheDirectory: serverCacheDirectory.path,
            serverUrl: server
        )
        historyRepository = created
        historyServerURL = server
        return created
    }

    /// Throws the copy of the server away: the session ended, or the server changed.
    func clearServerCache() {
        try? FileManager.default.removeItem(at: serverCacheDirectory)
        try? FileManager.default.createDirectory(at: serverCacheDirectory, withIntermediateDirectories: true)
    }

    /// The activities recorded by an older build get their local summary, in the background (TDD-0011).
    func backfillLocalSummaries() {
        DispatchQueue.global(qos: .utility).async { [store] in
            store.backfillLocalSummaries(limit: 20)
        }
    }

    /// TDD-0006 §5.1: after a kill or a relaunch by the system, continue an interruption that is recent, by
    /// itself. Anything older, or without precise "Always" location, is left for the user to decide
    /// (`RecorderViewModel.pendingRecovery`). Older interrupted activities are finished at their last known time.
    func resumeInterruptedIfRecent() {
        guard recorder.currentState == .idle else { return }
        let elapsedMs = nowElapsedRealtimeMs()
        let timeMs = nowTimeMs()
        guard let decision = store.resumeDecision(nowTimeMs: timeMs, hasPrecisePermission: tracker.canRecordInBackground)
        else { return }
        let finishedOlder = store.finishOlderInterrupted(
            keepId: decision.metadata.activityId,
            nowElapsedRealtimeMs: elapsedMs,
            nowTimeMs: timeMs
        )
        if finishedOlder > 0 { Task { await sync.syncNow() } }
        guard decision.action == ResumeAction.resumeNow else { return }
        let result = recorder.recover(
            activityId: decision.metadata.activityId,
            nowElapsedRealtimeMs: elapsedMs,
            nowTimeMs: timeMs
        )
        if result is RecoveryResult.Resumed {
            tracker.start()
            resumedAtLaunch = true
        } else {
            // A kill during STOPPING only needed finalizing; it is complete and ready to upload.
            Task { await sync.syncNow() }
        }
    }
}
