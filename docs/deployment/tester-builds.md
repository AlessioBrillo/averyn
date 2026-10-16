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

## After installing

Run the scenarios of the device matrix. In the app, **History** exports the GPX, the `diagnostics-v2` report and the raw file of any stored activity; attach the diagnostics to the results row together with its `buildId`.
