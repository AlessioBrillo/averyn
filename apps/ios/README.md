# iOS app

SwiftUI app. The Xcode project is **generated** from `project.yml` and not committed.

```sh
brew install xcodegen
cd apps/ios && xcodegen
open Averyn.xcodeproj    # the pre-build script builds the Kotlin `Shared` framework via Gradle
```

Requires macOS and Xcode 16+, and a JDK 21 (the pre-build script runs Gradle; see [tester-builds](../../docs/deployment/tester-builds.md) for installing the app on a phone). The `Shared` framework comes from `shared/ios-umbrella`, which re-exports `domain`, `tracking`, `metrics`, `sensors`, `sync`, `analysis`, `api` and `client`.

Native tracking lives here: `LocationTracker.swift` wraps `CLLocationManager` (background modes; `HealthKitExporter.swift` writes a finished workout to Health at the user's request, TDD-0014; no WatchConnectivity yet, a later milestone) and drives the shared `ActivityRecorder` from `shared/tracking` — see [TDD-0001](../../docs/design/TDD-0001-tracking-engine.md).

Sync: `AuthManager.swift` (server URL, sign-in with the server's identity provider through AppAuth, session in the Keychain) and `SyncService.swift` (runs `shared/sync` in the foreground and as a `BGProcessingTask`) — see [TDD-0002](../../docs/design/TDD-0002-activity-sync.md). The dev stack is plain HTTP, so **Debug** builds allow arbitrary loads (a build step in `project.yml`); Release builds are HTTPS-only.

SYNAPSE band: `SynapseBand.swift` is the iOS half of the band adapter (ADR-0030, [synapse.md](../../docs/integrations/synapse.md)): the standard heart-rate service for the recording, the consumer and research services into the shared `SynapseRecorder`, which writes the two sidecars under the user's consents. The band replaces the strap when the user chooses it in the settings (`BandPrefs`). **It was written without a Mac and has not been compiled or run**: build it (`xcodegen`, `xcodebuild -scheme Averyn -destination 'generic/platform=iOS Simulator'`) before trusting it.
