# Tester builds

How a build reaches the people running the [device matrix](../testing/device-matrix.md). Decisions: [ADR-0019](../adr/0019-crash-reporting-and-tester-builds.md) (store vitals only, no crash SDK), [ADR-0008](../adr/0008-project-codename.md) (technical ids fixed, display name "Averyn Beta"). Design: [TDD-0006 §5.8](../design/TDD-0006-field-readiness.md).

Tester builds go to Play **internal testing** and **TestFlight** only, never to a public listing. The application id and bundle id are `dev.averyn.app`; they cannot change after the first upload.

## What a build carries

- A version (`versionName` chosen when the build is made, `versionCode` = the workflow run number) and the **commit id** it was built from. The commit id is part of every exported diagnostics report (`device.buildId`), so a device-matrix row and a store crash report can be matched to the same build.
- The display name "Averyn Beta".

## Android

The manual workflow **Release (tester build)** (`.github/workflows/release.yml`) builds `:apps:android:bundleRelease` and attaches the signed `.aab` to the run. Locally, the same build needs the same environment:

```sh
AVERYN_KEYSTORE_FILE=/path/to/upload.jks AVERYN_KEYSTORE_PASSWORD=... AVERYN_KEY_ALIAS=... AVERYN_KEY_PASSWORD=... \
  ./gradlew :apps:android:bundleRelease -Paveryn.versionCode=12 -Paveryn.versionName=0.1.0-beta.1 -Paveryn.commit=$(git rev-parse HEAD)
```

Without `AVERYN_KEYSTORE_FILE` there is no release signing configuration (a developer build is unaffected).

**One-time setup, done by the owner** (it needs accounts and keys this repository must never hold):

1. A Google Play Console app with the id `dev.averyn.app`, an internal testing track and the testers' addresses.
2. An upload keystore (`keytool -genkeys ...`), kept offline. Repository secrets: `AVERYN_KEYSTORE_BASE64` (the keystore, base64), `AVERYN_KEYSTORE_PASSWORD`, `AVERYN_KEY_ALIAS`, `AVERYN_KEY_PASSWORD`.
3. Upload the downloaded `.aab` to the internal track in the Play Console.

## iOS

Built on a Mac, then uploaded to TestFlight (App Store Connect):

1. An App Store Connect app with the bundle id `dev.averyn.app`, an Apple Developer team and the testers added to TestFlight.
2. `xcodegen` in `apps/ios`, set the team in Xcode, then archive. To record the commit in the diagnostics: `xcodebuild archive ... AVERYN_COMMIT=$(git rev-parse HEAD) CURRENT_PROJECT_VERSION=<n> MARKETING_VERSION=<x.y.z>`.
3. Upload with Xcode's Organizer (or Transporter).

A CI job for this is deliberately not written yet: it cannot be tested without the signing material, and untested signing automation is worse than the checklist above.

## By hand, before any store account (campaign start)

The first campaign ([ADR-0028](../adr/0028-consumer-launch-target.md)) does not wait for the stores. Both builds below carry the commit id, so a matrix row names its build exactly as a store build would.

**Android** (a debug build installed over USB; enable *USB debugging* in the developer options of the phone):

```sh
# JAVA_HOME must point at JDK 21; adb is in <sdk.dir>/platform-tools (sdk.dir is in local.properties)
./gradlew :apps:android:installDebug -Paveryn.versionName=0.1.0-dev.1 -Paveryn.commit=$(git rev-parse HEAD)
adb devices          # the phone must be listed as "device", not "unauthorized"
```

With several phones, add `-Pandroid.injected.device.serial=<serial>` or run `adb -s <serial> install -r apps/android/build/outputs/apk/debug/*.apk` once per phone. A debug build is not optimised the way a release build is; rows about battery drain (S8, S11) are repeated on a release build once signing exists.

**iPhone** (from the Mac; a free Apple ID is enough, the profile lasts 7 days and the app must be re-installed after that):

1. `brew install xcodegen`, then in `apps/ios`: `xcodegen`.
2. Open `Averyn.xcodeproj`, select the target, *Signing & Capabilities*: choose your Apple ID team and let Xcode manage signing (`xcodegen` regenerates the project and forgets the team: choose it again after every run). Change the bundle id only for this local install if Xcode refuses `dev.averyn.app`, and note it in the matrix row.
3. Select the iPhone as the destination and run. For the diagnostics to carry the commit: *Edit Scheme → Run → Arguments* is not enough; build with `xcodebuild ... AVERYN_COMMIT=$(git rev-parse HEAD)` or set `AVERYN_COMMIT` in the target's build settings before running.
4. On the phone: *Settings → General → VPN & Device Management* → trust the developer profile.

HealthKit and the background location mode need a real device and the capability enabled for your team; if Xcode reports a missing capability, enable it in *Signing & Capabilities* and note it.

**The server for S12–S14 and later rows.** The phone must reach a backend over TLS. Use the Compose stack of [self-hosting.md](self-hosting.md) with its [TLS overlay](self-hosting.md#tls-public-instance): it needs two public names with working certificates (the issuer is fixed once chosen), so it runs on a small rented host or on a machine with a domain pointing at it, not on a bare local address. This is the first thing that needs a decision from the owner (the host); until then S1–S11 and S25 need no server. The address goes in the app under the account section (later under *Advanced*).

**Order of the first run.** One phone, S1, S2 and S5 first: if the recording itself is wrong, nothing else matters. Fill a row the same day, with the exported diagnostics, in a private folder.

## After installing

Run the scenarios of the device matrix. In the app, **History** exports the GPX, the `diagnostics-v4` report and the raw file of any stored activity; attach the diagnostics to the results row together with its `buildId`.
