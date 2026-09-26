# iOS app

SwiftUI app. The Xcode project is **generated** from `project.yml` and not committed.

```sh
brew install xcodegen
cd apps/ios && xcodegen
open Averyn.xcodeproj    # the pre-build script builds the Kotlin `Shared` framework via Gradle
```

Requires macOS and Xcode 16+. The `Shared` framework comes from `shared/ios-umbrella`, which re-exports `domain`, `tracking` and `metrics`.

Native tracking (Core Location, background modes, HealthKit, WatchConnectivity) lives here and implements `LocationSource` from `shared/tracking` — see [TDD-0001](../../docs/design/TDD-0001-tracking-engine.md).
