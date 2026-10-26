@preconcurrency import BackgroundTasks
import Foundation
import Shared

/// Uploads finished activities (TDD-0002): the upload logic, retry classification and per-activity status all
/// live in `shared/sync`; this only supplies a fresh access token and decides when to run it (foreground, and a
/// `BGProcessingTask` when something is left to retry).
@MainActor
final class SyncService: ObservableObject {
    static let taskIdentifier = "dev.averyn.app.sync"

    @Published private(set) var statusText = ""

    private let store: ActivityStore
    private let auth: AuthManager
    private var running = false

    init(store: ActivityStore, auth: AuthManager) {
        self.store = store
        self.auth = auth
    }

    /// One upload pass. Returns true when nothing is left that retrying could still deliver. Safe to call often:
    /// a pass already running is not doubled, and the endpoint is idempotent.
    @discardableResult
    func syncNow() async -> Bool {
        guard !running, let server = auth.normalizedServerURL else { return true }
        running = true
        defer {
            running = false
            refreshStatus()
        }

        let finished: Bool
        do {
            let token = try await auth.freshAccessToken()
            let sync = ActivitySync.companion.create(store: store, serverUrl: server)
            defer { sync.close() }
            let result = try await sync.syncPending(accessToken: token)
            if result.needsLogin {
                // S14: a refused / revoked session must be observable as FAILED_RETRYABLE (AUTH) so the UI
                // can prompt the user to sign in again. Mark every queued/uploading activity accordingly.
                let statusStore = SyncStatusStore(store: store)
                for metadata in store.list() {
                    let current = statusStore.statusOf(activityId: metadata.activityId)
                    if current.state == .queued || current.state == .uploading {
                        statusStore.write(
                            activityId: metadata.activityId,
                            status: SyncStatus(
                                state: .failedRetryable,
                                attempts: current.attempts,
                                lastError: "AUTH",
                                serverActivityId: current.serverActivityId
                            )
                        )
                    }
                }
            }
            // Sign-in needed: a background retry would just fail again; signing in starts a fresh pass.
            finished = !(result.retryable > 0 && !result.needsLogin)
        } catch {
            finished = false // no network, or the IdP is unreachable
        }
        if !finished { scheduleBackgroundSync() }
        return finished
    }

    func refreshStatus() {
        guard let latest = store.list().max(by: { $0.startedAtMs < $1.startedAtMs }) else {
            statusText = tr("Sync: no activities yet")
            return
        }
        let status = SyncStatusStore(store: store).statusOf(activityId: latest.activityId)
        statusText = tr("Sync: %@", "\(status.state)") + (status.lastError.map { " (\($0))" } ?? "")
    }

    /// Must run before the app finishes launching: call from `AverynApp.init`.
    func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.taskIdentifier, using: nil) { [weak self] task in
            // The handler is called on a background queue: hop to the main actor for the work.
            let work = Task { @MainActor in
                let finished = await self?.syncNow() ?? true
                task.setTaskCompleted(success: finished)
            }
            task.expirationHandler = { work.cancel() }
        }
    }

    private func scheduleBackgroundSync() {
        let request = BGProcessingTaskRequest(identifier: Self.taskIdentifier)
        request.requiresNetworkConnectivity = true
        try? BGTaskScheduler.shared.submit(request)
    }
}
