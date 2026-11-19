# Android app

Jetpack Compose app and the native tracking adapter. The shared logic lives in
`shared/`; the design is in [TDD-0001](../../docs/design/TDD-0001-tracking-engine.md)
(tracking) and [TDD-0015](../../docs/design/TDD-0015-consumer-ux.md) (the three
moments).

## Requirements

- JDK 21.
- Android Studio or the Android SDK; put `sdk.dir` in `local.properties`
  (git-ignored).

## Build and test

```sh
./gradlew :apps:android:assembleDebug
./gradlew :apps:android:lintDebug
./gradlew :apps:android:testDebugUnitTest
```

The release code used by the device campaign is a separate build type:

```sh
./gradlew :apps:android:assembleCampaign -Paveryn.versionName=0.1.0-dev.1 -Paveryn.commit=$(git rev-parse HEAD)
adb install -r apps/android/build/outputs/apk/campaign/android-campaign.apk
```

It is not debuggable and is signed with the debug key, so it needs no keystore.
The full tester-build process is in
[docs/deployment/tester-builds.md](../../docs/deployment/tester-builds.md).

## Where things are

- `src/main/kotlin/dev/averyn/android/tracking/`: the `LocationManager`
  adapter and the foreground service.
- `src/main/kotlin/dev/averyn/android/ui/`: Compose screens.
- `src/main/kotlin/dev/averyn/android/sync/`: WorkManager sync and sign-in.

The app talks to the backend over `/v1`; the server address is under the
account settings.
