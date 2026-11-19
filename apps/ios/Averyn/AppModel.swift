import Foundation
@preconcurrency import Shared
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
    let heartRate: HeartRateMonitor
    /// The SYNAPSE band; used instead of the strap when the user chose it (`BandPrefs.useBand`).
    let band: SynapseBand
    let auth: AuthManager
    let sync: SyncService
    /// Where the activity files live; the raw file of an activity is `<id>.jsonl` in here (ADR-0015).
    let activitiesDirectory: URL
    /// Where the copy of the server lives (TDD-0011): its own directory, excluded from the backup.
    let serverCacheDirectory: URL
    /// Where the profile lives: the user's own settings, kept with or without an account (backed up, not in the cache).
    let profileDirectory: URL

    /// The history of the account: the server, the copy and the device. Rebuilt when the server changes.
    private var historyRepository: HistoryRepository?
    private var historyServerURL: String?

    /// True when this launch resumed an interrupted activity by itself; the UI says so once.
    private(set) var resumedAtLaunch = false
    /// The recovery of this launch, finished when it has resumed, finalized or left the interruption to the user.
    private(set) var recovery: Task<Void, Never>?

    /// Whether RR intervals (Health class) may be recorded right now. Consent is server-authoritative
    /// (ADR-0021); this cache is populated from GET /v1/consents and failed closed (false) until then.
    var hasRrConsent = false

    /// Recorder listeners besides the internal heart-rate lifecycle. `RecorderViewModel` registers here
    /// instead of overwriting `recorder.listener` (single-callback problem).
    private var recorderListeners: [(LiveSnapshot) -> Void] = []
    private let recorderListenersLock = NSLock()

    /// Asks the server for the consent again while an activity is recording, so a consent withdrawn in the
    /// meantime stops the RR intervals without the app coming to the foreground (ADR-0021; same period as the
    /// Android watchdog). The background location session keeps this run loop alive.
    private var consentTimer: Timer?
    private static let consentRefreshInterval: TimeInterval = 2 * 60
    /// True while the strap session of this activity runs, so that `startHeartRate`, called on every fix, starts it once.
    private var heartRateActive = false
    /// The sensor of this activity (the band or the strap), fixed when it starts so stopping reaches the same one.
    private var activeSource: HeartRateSource?

    private init() {
        let dir = FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("activities", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        activitiesDirectory = dir
        store = ActivityStore.companion.open(directoryPath: dir.path)

        // The copy of the server holds the coordinates of the activities of the account: it is not in the
        // backup of the device (TDD-0011).
        var cacheDir = FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("server-cache", isDirectory: true)
        try? FileManager.default.createDirectory(at: cacheDir, withIntermediateDirectories: true)
        var cacheValues = URLResourceValues()
        cacheValues.isExcludedFromBackup = true
        try? cacheDir.setResourceValues(cacheValues)
        serverCacheDirectory = cacheDir
        profileDirectory = FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("profile", isDirectory: true)

        UIDevice.current.isBatteryMonitoringEnabled = true
        let recorder = ActivityRecorder(store: store)
        recorder.batteryPercent = {
            let level = UIDevice.current.batteryLevel
            return level >= 0 ? KotlinInt(int: Int32(level * 100)) : nil
        }
        self.recorder = recorder
        heartRate = HeartRateMonitor()
        band = SynapseBand(store: store)
        tracker = LocationTracker(recorder: recorder)
        auth = AuthManager()
        sync = SyncService(store: store, auth: auth)

        // Heart-rate lifecycle: stop HR when the activity reaches a terminal state. Registered
        // listeners (e.g. RecorderViewModel) are called after the internal logic.
        recorder.listener = { [weak self] snapshot in
            if snapshot.state.isTerminal {
                Task { @MainActor in self?.heartRateActive = false }
                Task { @MainActor in
                    let source = self?.activeSource
                    self?.activeSource = nil
                    try? await source?.stop()
                }
                Task { @MainActor in self?.stopConsentRefresh() }
            }
            self?.recorderListenersLock.lock()
            let listeners = self?.recorderListeners ?? []
            self?.recorderListenersLock.unlock()
            for listener in listeners {
                listener(snapshot)
            }
        }

        // Start HR when the first GPS fix arrives (gpsReady is called from LocationTracker).
        tracker.onGpsReady = { [weak self] in
            Task { await self?.startHeartRate() }
        }

        // Consent is server-authoritative: refresh it now (fail-closed), and again when the app comes
        // to the foreground (see AverynApp.scenePhase).
        refreshConsents()
    }

    /// Starts the heart-rate adapter once there is an activity to feed. Called from LocationTracker on the fixes of
    /// a RECORDING activity: the first one, and also the first one after a recovery, which is RECORDING without
    /// ever passing through PREPARING (the Android service does the same). Best-effort: a missing strap never
    /// stops GPS tracking.
    private func startHeartRate() {
        guard !heartRateActive, let activityId = recorder.currentActivityId else { return }
        heartRateActive = true
        startConsentRefresh()
        let source: HeartRateSource = BandPrefs.useBand ? band : heartRate
        activeSource = source
        source.onSample = { [weak self] sample in
            self?.feedHeartRate(sample)
        }
        source.onRrInterval = { [weak self] rr in
            self?.feedRrInterval(rr)
        }
        source.onInvalid = { [weak self] in
            self?.recorder.recordDroppedInput(reason: .invalidHeartRate)
        }
        if source.isAvailable {
            Task {
                do {
                    try await source.start(sessionId: activityId)
                } catch {
                    // Sensor errors are non-fatal: recording continues without HR.
                }
            }
        }
    }

    /// Registers a listener for recorder snapshots. Used by `RecorderViewModel` instead of
    /// overwriting `recorder.listener` directly (single-callback problem).
    func addRecorderListener(_ listener: @escaping (LiveSnapshot) -> Void) {
        recorderListenersLock.lock()
        recorderListeners.append(listener)
        recorderListenersLock.unlock()
    }

    /// Refreshes consent grants from the server (ADR-0021). Uses the shared client so the consent
    /// read path is implemented once, not per platform. Fail-closed: no answer means no RR.
    func refreshConsents() {
        Task {
            guard let token = try? await auth.freshAccessToken(),
                  let server = auth.normalizedServerURL,
                  !server.isEmpty else {
                setConsents(scopes: [])
                return
            }
            do {
                let result = try await history().client.consents(accessToken: token)
                // A failure carries no value: no grants, so no RR (fail-closed).
                let grants = (result.value as? [ConsentGrant]) ?? []
                setConsents(scopes: grants.map { $0.scope })
            } catch {
                setConsents(scopes: [])
            }
        }
    }

    private func startConsentRefresh() {
        consentTimer?.invalidate()
        consentTimer = Timer.scheduledTimer(withTimeInterval: Self.consentRefreshInterval, repeats: true) { [weak self] _ in
            Task { @MainActor in
                self?.refreshConsents()
                // A strap switched on after the start is looked for again (the first scan gives up after a few seconds).
                self?.activeSource?.searchAgainIfNeverFound()
            }
        }
    }

    private func stopConsentRefresh() {
        consentTimer?.invalidate()
        consentTimer = nil
    }

    /// The consents the server last listed (ADR-0021), for both sensors, whichever is in use: a consent revoked
    /// while the band was off must not come back when it is switched on again. Empty = nothing granted.
    private func setConsents(scopes: [String]) {
        let granted = scopes.contains("rr_recording")
        hasRrConsent = granted
        heartRate.recordRrIntervals = granted
        band.recordRrIntervals = granted
        band.consents = scopes.isEmpty
            ? SynapseConsents.companion.NONE
            : SynapseConsents.companion.fromScopes(scopes: scopes)
    }

    /// Forwards an HR sample from the adapter to the recorder while an activity is RECORDING.
    func feedHeartRate(_ sample: HeartRateSample) {
        recorder.onHeartRate(sample: sample)
    }

    /// Forwards an RR interval from the adapter to the consent-gated sidecar.
    func feedRrInterval(_ rr: RrInterval) {
        guard hasRrConsent, let activityId = recorder.currentActivityId else { return }
        if !store.hasRr(activityId: activityId) {
            if let metadata = recorder.currentMetadata() {
                store.createRrMetadata(metadata: metadata)
            }
        }
        store.appendRrInterval(activityId: activityId, rr: rr)
    }

    /// The history of the account (TDD-0011): the server, the copy on this device and the local files.
    func history() -> HistoryRepository {
        let server = auth.normalizedServerURL ?? ""
        if let current = historyRepository, historyServerURL == server { return current }
        let created = HistoryRepository.companion.create(
            store: store,
            cacheDirectory: serverCacheDirectory.path,
            serverUrl: server,
            profileDirectory: profileDirectory.path
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
        // Read on the main actor, where the recorder lives: the activity being prepared right now is not that file.
        let live = recorder.currentActivityId
        DispatchQueue.global(qos: .utility).async { [store] in
            // Killed before the first fix: nothing was recorded, and the file would stay for good.
            store.discardEmptyPreparing(exceptId: live)
            store.backfillLocalSummaries(limit: 20)
        }
    }

    /// TDD-0006 §5.1: after a kill or a relaunch by the system, continue an interruption that is recent, by
    /// itself. Anything older, or without precise "Always" location, is left for the user to decide
    /// (`RecorderViewModel.pendingRecovery`). Older interrupted activities are finished at their last known time.
    ///
    /// Reading the stored files grows with the activity (a 3 h one is ~10k lines, read more than once), so it runs
    /// off the main thread: a slow launch is killed by iOS. Only the recorder, which is single-threaded, is touched
    /// back on the main actor. `recovery` is what the view model awaits before it looks for something to ask.
    func resumeInterruptedIfRecent() {
        guard recorder.currentState == .idle else { return }
        let elapsedMs = nowElapsedRealtimeMs()
        let timeMs = nowTimeMs()
        let hasPrecisePermission = tracker.canRecordInBackground
        let store = self.store
        recovery = Task {
            let plan = await Task.detached(priority: .userInitiated) {
                store.planLaunchRecovery(
                    nowElapsedRealtimeMs: elapsedMs,
                    nowTimeMs: timeMs,
                    hasPrecisePermission: hasPrecisePermission
                )
            }.value
            if plan.finishedOlder > 0 { Task { await sync.syncNow() } }
            // The user may have pressed Start while the files were read: that recording owns the recorder.
            guard let decision = plan.decision, decision.action == ResumeAction.resumeNow, recorder.currentState == .idle
            else { return }
            applyRecovery(decision, elapsedMs: elapsedMs, timeMs: timeMs)
        }
    }

    private func applyRecovery(_ decision: ResumeDecision, elapsedMs: Int64, timeMs: Int64) {
        // A full disk makes the replay's RECOVERED event fail: the file stays interrupted and the user is asked at
        // the next launch, instead of the app being killed for it.
        guard let result = try? recorder.recover(
            activityId: decision.metadata.activityId,
            nowElapsedRealtimeMs: elapsedMs,
            nowTimeMs: timeMs
        ) else { return }
        if result is RecoveryResult.Resumed {
            tracker.start()
            resumedAtLaunch = true
        } else {
            // A kill during STOPPING only needed finalizing; it is complete and ready to upload.
            Task { await sync.syncNow() }
        }
    }
}
