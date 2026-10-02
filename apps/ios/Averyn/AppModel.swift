import Foundation
import Shared

/// The few long-lived objects the UI and the background sync task share.
@MainActor
final class AppModel {
    static let shared = AppModel()

    let store: ActivityStore
    let auth: AuthManager
    let sync: SyncService

    private init() {
        let dir = FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("activities", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        store = ActivityStore.companion.open(directoryPath: dir.path)
        auth = AuthManager()
        sync = SyncService(store: store, auth: auth)
    }
}
