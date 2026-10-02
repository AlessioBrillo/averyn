# iOS app

SwiftUI app. The Xcode project is **generated** from `project.yml` and not committed.

```sh
brew install xcodegen
cd apps/ios && xcodegen
open Averyn.xcodeproj    # the pre-build script builds the Kotlin `Shared` framework via Gradle
```

Requires macOS and Xcode 16+. The `Shared` framework comes from `shared/ios-umbrella`, which re-exports `domain`, `tracking`, `metrics` and `sync`.

Native tracking lives here: `LocationTracker.swift` wraps `CLLocationManager` (background modes, no HealthKit/WatchConnectivity yet — those are later milestones) and drives the shared `ActivityRecorder` from `shared/tracking` — see [TDD-0001](../../docs/design/TDD-0001-tracking-engine.md).

Sync: `AuthManager.swift` (server URL, sign-in with the server's identity provider through AppAuth, session in the Keychain) and `SyncService.swift` (runs `shared/sync` in the foreground and as a `BGProcessingTask`) — see [TDD-0002](../../docs/design/TDD-0002-activity-sync.md). The dev stack is plain HTTP, so the app currently allows arbitrary loads (see `project.yml`): internal builds only.
