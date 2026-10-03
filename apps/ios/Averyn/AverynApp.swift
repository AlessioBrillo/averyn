import SwiftUI

@main
struct AverynApp: App {
    @Environment(\.scenePhase) private var scenePhase

    init() {
        // BGTaskScheduler handlers must be registered before the app finishes launching.
        AppModel.shared.sync.register()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
        .onChange(of: scenePhase) { _, phase in
            // Anything finished earlier and not uploaded yet (offline, killed, signed out) goes up now.
            if phase == .active { Task { await AppModel.shared.sync.syncNow() } }
        }
    }
}
