import Shared
import SwiftUI

@main
struct AverynApp: App {
    var body: some Scene {
        WindowGroup {
            // Placeholder that proves the app links against shared/. Replaced by the Record screen in MVP-0.
            Text("Averyn — \(ActivityState.idle.name)")
        }
    }
}
