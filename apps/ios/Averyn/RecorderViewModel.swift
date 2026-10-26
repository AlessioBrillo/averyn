import Foundation
@preconcurrency import Shared
import UIKit

/// One row of the history list (TDD-0006 R8): an activity stored on this device.
struct HistoryRow: Identifiable {
    let id: String
    let title: String
    let state: String
    let sync: String
}

/// The files prepared for sharing one stored activity.
struct PreparedExport {
    let gpx: URL
    let diagnostics: URL
    let raw: URL
}

@MainActor
final class RecorderViewModel: ObservableObject {
    let store: ActivityStore
    let recorder: ActivityRecorder
    private let tracker: LocationTracker
    private let activitiesDirectory: URL

    @Published var snapshot: LiveSnapshot
    @Published var pendingRecovery: ActivityMetadata?
    @Published var exportURL: URL?
    @Published var diagnostics: DiagnosticsReport?
    @Published var diagnosticsURL: URL?
    /// F5: a recovery that lost store lines is surfaced, never silent — see RecoveryResult.droppedRecordCount.
    @Published var recoveryWarning: String?
    /// Something the user should know right now: an automatic resume, a lost permission.
    @Published var notice: String?
    @Published var history: [HistoryRow] = []
    @Published var preparedExports: [String: PreparedExport] = [:]
    /// The list of the account (TDD-0011): the server, the copy and the activities that are only here.
    @Published var historySnapshot: HistorySnapshot?
    /// The activity that is open, with its analysis.
    @Published var activityDetail: DetailSnapshot?
    /// The activities stored on this device (TDD-0011 "dati sul dispositivo").
    @Published var deviceActivities: [DeviceActivityItem] = []
    /// The unit system the numbers are written in (TDD-0012): the one the profile last said, remembered on the
    /// device so it holds offline.
    @Published var units: UnitSystem = .metric
    /// The profile of the account, once the server answered; nil until then (and without a session).
    @Published var profile: UserProfile?
    /// What the profile form tells the user after a save.
    @Published var profileMessage: String?

    init() {
        let model = AppModel.shared
        store = model.store
        recorder = model.recorder
        tracker = model.tracker
        activitiesDirectory = model.activitiesDirectory
        snapshot = recorder.snapshot()
        // TDD-0001 F4: whatever the launch did not resume by itself (see AppModel.resumeInterruptedIfRecent) is
        // put to the user once. A recorder that is already recording owns its activity.
        // Pick the newest interrupted activity (file listing order is arbitrary — see m5).
        pendingRecovery = recorder.currentState == .idle
            ? store.interrupted().sorted { $0.startedAtMs > $1.startedAtMs }.first
            : nil
        notice = model.resumedAtLaunch ? "Recording resumed after an interruption" : nil

        AppModel.shared.addRecorderListener { [weak self] newSnapshot in
            DispatchQueue.main.async { self?.snapshot = newSnapshot }
        }
        tracker.onIssue = { [weak self] message in
            DispatchQueue.main.async { self?.notice = message }
        }
        refreshHistory()
        // The activities of an older build get their local summary, so their numbers are there even offline.
        model.backfillLocalSummaries()
    }

    /// The token of the session, or nil when there is none (the list then shows the copy on this device).
    private func accessToken() async -> String? {
        try? await AppModel.shared.auth.freshAccessToken()
    }

    /// Reads the list of the account: what the server has, plus the activities that are only here.
    func refreshAccountHistory() async {
        let repository = AppModel.shared.history()
        // What is on the device comes first: the list is there at once, the server fills it in.
        historySnapshot = try? await repository.loadCached()
        deviceActivities = (try? await repository.deviceData()) ?? []
        if let cached = try? await repository.cachedUnits() { units = cached }
        if let refreshed = try? await repository.refresh(accessToken: await accessToken()) {
            historySnapshot = refreshed
        }
        deviceActivities = (try? await repository.deviceData()) ?? []
    }

    /// Reads the profile of the account (TDD-0012) and the unit system with it.
    func loadProfile() async {
        let repository = AppModel.shared.history()
        guard let snapshot = try? await repository.refreshProfile(accessToken: await accessToken()) else { return }
        units = snapshot.units
        profile = snapshot.profile
        profileMessage = snapshot.profile == nil ? "Could not load your profile." : nil
    }

    /// Saves the profile. The server checks it again: a profile it refuses changes nothing.
    func saveProfile(units chosen: UnitSystem, maxHr: MaxHrInput) async {
        guard maxHr.valid, let token = await accessToken() else {
            profileMessage = "Sign in again to save your profile."
            return
        }
        let repository = AppModel.shared.history()
        let wanted = UserProfile(units: chosen, maxHrBpm: maxHr.value)
        guard let saved = try? await repository.saveProfile(accessToken: token, profile: wanted) else {
            profileMessage = "Could not save your profile."
            return
        }
        if let stored = saved.profile {
            units = saved.units
            profile = stored
            profileMessage = "Saved"
        } else {
            profileMessage = saved.failure?.status?.intValue == 422
                ? "That is not a valid profile."
                : "Could not save your profile."
        }
    }

    /// Deletes the account on the server (ADR-0017) and ends the session. The copy of the server goes with it; the
    /// recordings on this device stay. False when the server could not be asked: nothing was deleted, try again.
    func deleteAccount() async -> Bool {
        guard let token = await accessToken() else { return false }
        let repository = AppModel.shared.history()
        guard let result = try? await repository.deleteAccount(accessToken: token), result.failure == nil else {
            return false
        }
        AppModel.shared.auth.signOut()
        historySnapshot = nil
        activityDetail = nil
        profile = nil
        units = .metric
        return true
    }

    /// What the last "save to Health" said, for the screen.
    @Published var healthMessage: String?

    /// Saves an activity recorded on this phone to Health (TDD-0014), at the user's request.
    func saveToHealth(_ item: DeviceActivityItem) async {
        healthMessage = nil
        do {
            switch try await HealthKitExporter().save(store: store, activityId: item.activityId) {
            case .saved: healthMessage = "Saved to Health"
            case .alreadySaved: healthMessage = "This activity is already in Health."
            }
        } catch HealthKitExporter.Failure.unavailable {
            healthMessage = "Health is not available on this device."
        } catch HealthKitExporter.Failure.denied {
            healthMessage = "Averyn was not allowed to write to Health. You can allow it in the Health settings."
        } catch HealthKitExporter.Failure.nothingToSave {
            healthMessage = "This activity cannot be saved to Health: it is not finished or has no route."
        } catch {
            healthMessage = "Could not save to Health."
        }
    }

    /// Reads the next page of the list.
    func loadMoreHistory() async {
        guard let snapshot = historySnapshot, let cursor = snapshot.nextCursor else { return }
        let repository = AppModel.shared.history()
        if let more = try? await repository.loadMore(accessToken: await accessToken(), cursor: cursor) {
            historySnapshot = more
        }
    }

    /// Opens an activity: its numbers first, its analysis right after (the server computes it on the first read).
    func openDetail(_ item: HistoryItem) async {
        guard let serverId = item.serverId else { return }
        let repository = AppModel.shared.history()
        let token = await accessToken()
        activityDetail = try? await repository.refreshDetail(serverId: serverId, accessToken: token)
        var current = try? await repository.refreshAnalysis(serverId: serverId, accessToken: token)
        var attempts = 0
        while let snapshot = current, snapshot.analysisState == .computing, attempts < 3 {
            // A nullable number crosses the Kotlin boundary as an NSNumber.
            let wait = snapshot.failure?.retryAfterSeconds?.int64Value ?? 5
            try? await Task.sleep(for: .seconds(wait))
            current = try? await repository.refreshAnalysis(serverId: serverId, accessToken: token)
            attempts += 1
        }
        if let current { activityDetail = current }
    }

    /// Throws one activity off this device: the files go, the server keeps what it has.
    func deleteLocal(_ item: DeviceActivityItem) async {
        let repository = AppModel.shared.history()
        try? await repository.deleteLocal(activityId: item.activityId)
        deviceActivities = (try? await repository.deviceData()) ?? []
        refreshHistory()
    }

    func start(sport: Sport) {
        // start() is legal from IDLE or a terminal state (it resets terminal to IDLE internally); anything else
        // would throw IllegalStateException (C3: double-tap / out-of-order tap must not kill the app).
        guard recorder.currentState == .idle || recorder.currentState.isTerminal else { return }
        let id = UUID().uuidString
        recorder.start(activityId: id, sport: sport, elapsedRealtimeMs: nowElapsedRealtimeMs(), timeMs: nowTimeMs())
        tracker.start()
    }

    func pause() {
        guard recorder.currentState == .recording else { return }
        recorder.pause(elapsedRealtimeMs: nowElapsedRealtimeMs(), timeMs: nowTimeMs(), reason: .user)
    }

    func resume() {
        guard recorder.currentState == .paused else { return }
        recorder.resume(elapsedRealtimeMs: nowElapsedRealtimeMs(), timeMs: nowTimeMs())
    }

    func stop() {
        guard recorder.currentState == .recording || recorder.currentState == .paused else { return }
        let completed = recorder.stop(elapsedRealtimeMs: nowElapsedRealtimeMs(), timeMs: nowTimeMs())
        tracker.stop()
        export(activityId: completed.activityId, diagnostics: completed.diagnostics)
    }

    /// The user gave up while waiting for the first fix: nothing was recorded, so nothing is kept.
    func cancel() {
        guard recorder.currentState == .preparing else { return }
        recorder.cancel()
        tracker.stop()
        refreshHistory()
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
        refreshHistory()
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
        refreshHistory()
    }

    private func writeDiagnosticsJson(activityId: String, diagnostics: DiagnosticsReport) {
        let json = diagnostics.toJson(device: currentDeviceInfo())
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(activityId)-diagnostics.json")
        try? json.write(to: url, atomically: true, encoding: .utf8)
        diagnosticsURL = url
    }

    /// No device identifiers (docs/privacy/data-classification.md) — just what a device-matrix row needs.
    private func currentDeviceInfo() -> DeviceInfo {
        DeviceInfo(
            model: hardwareIdentifier(),
            osVersion: "iOS \(UIDevice.current.systemVersion)",
            appVersion: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "unknown",
            manufacturer: "Apple",
            buildId: Bundle.main.infoDictionary?["AverynCommit"] as? String,
            locationPermission: tracker.permissionLabel,
            powerSaverOn: KotlinBoolean(bool: ProcessInfo.processInfo.isLowPowerModeEnabled),
            batteryOptimisationExempt: nil
        )
    }

    /// Reloads the list of stored activities (newest first). The sync status of each is a small file read.
    func refreshHistory() {
        let store = self.store
        DispatchQueue.global(qos: .utility).async {
            let statuses = SyncStatusStore(store: store)
            let formatter = DateFormatter()
            formatter.dateStyle = .medium
            formatter.timeStyle = .short
            let rows = store.list()
                .sorted { $0.startedAtMs > $1.startedAtMs }
                .prefix(20)
                .map { metadata in
                    HistoryRow(
                        id: metadata.activityId,
                        title: "\(metadata.sport.name) \(formatter.string(from: Date(timeIntervalSince1970: Double(metadata.startedAtMs) / 1000)))",
                        state: store.lastStateOf(activityId: metadata.activityId).map { "\($0)" } ?? "?",
                        sync: "\(statuses.statusOf(activityId: metadata.activityId).state)"
                    )
                }
            DispatchQueue.main.async { [weak self] in
                self?.history = Array(rows)
            }
        }
    }

    /// Writes the GPX and the diagnostics of a stored activity next to each other (not in `tmp`, which the system
    /// may purge) and exposes them, with the raw file, for sharing (TDD-0006 R8).
    func prepareExport(activityId: String) {
        let store = self.store
        let device = currentDeviceInfo()
        let raw = activitiesDirectory.appendingPathComponent("\(activityId).jsonl")
        let directory = FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("exports", isDirectory: true)
        DispatchQueue.global(qos: .utility).async {
            try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let gpx = directory.appendingPathComponent("\(activityId).gpx")
            let diagnostics = directory.appendingPathComponent("\(activityId)-diagnostics.json")
            store.exportGpxToFile(activityId: activityId, filePath: gpx.path, content: .raw)
            if let report = store.diagnosticsOf(activityId: activityId) {
                try? report.toJson(device: device).write(to: diagnostics, atomically: true, encoding: .utf8)
            }
            DispatchQueue.main.async { [weak self] in
                self?.preparedExports[activityId] = PreparedExport(gpx: gpx, diagnostics: diagnostics, raw: raw)
            }
        }
    }
}

/// The hardware identifier (e.g. "iPhone15,2"): `UIDevice.current.model` only says "iPhone", which cannot
/// separate devices in the device matrix.
private func hardwareIdentifier() -> String {
    var info = utsname()
    uname(&info)
    return withUnsafePointer(to: &info.machine) {
        $0.withMemoryRebound(to: CChar.self, capacity: 1) { String(cString: $0) }
    }
}

private func droppedRecordWarning(_ droppedRecordCount: Int32) -> String? {
    droppedRecordCount > 0 ? "Recovery lost \(droppedRecordCount) corrupted record(s) from the interrupted activity" : nil
}
