import SwiftUI

@main
struct AverynApp: App {
    @Environment(\.scenePhase) private var scenePhase

    init() {
        // BGTaskScheduler handlers must be registered before the app finishes launching.
        AppModel.shared.sync.register()
        // Also runs when iOS relaunches a terminated app in the background to keep recording (significant-change
        // location event): no scene exists then, so it cannot wait for a view model (TDD-0006 §5.1).
        AppModel.shared.resumeInterruptedIfRecent()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
        .onChange(of: scenePhase) { _, phase in
            // Anything finished earlier and not uploaded yet (offline, killed, signed out) goes up now.
            if phase == .active {
                Task {
                    await AppModel.shared.sync.syncNow()
                    AppModel.shared.refreshConsents()
                }
            }
        }
    }
}
