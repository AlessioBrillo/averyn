# Shared Kotlin Multiplatform modules

Pure Kotlin logic shared by the backend and both apps. The modules target the
JVM, Android and iOS; `shared/ios-umbrella` re-exports them as the `Shared`
framework for the Swift app.

| Module | Contents |
|---|---|
| `domain` | DTOs and the domain model |
| `tracking` | the durable local store, replay, the state machine |
| `metrics` | distance, time, speed, elevation, GPS quality, heart rate, zones, splits, best efforts |
| `analysis` | the analysis of an activity and its series |
| `sensors` | BLE heart-rate and Synapse frame parsers |
| `sync` | the sync protocol and conflict handling |
| `api` | the contract of the endpoints (DTOs only) |
| `client` | the read path of the apps (Ktor client, the server copy on the device, history merge) |
| `ios-umbrella` | the `Shared` framework entry point |

## Rules

- `domain` and `metrics` are pure Kotlin with **no platform dependencies**.
- Raw location samples are immutable; derived data is versioned by
  `algorithm_version`.
- A behavior change in a metric is a new version and a new
  [metric spec](../docs/metrics/README.md) section; existing versions are
  never edited.

## Test

```sh
./gradlew :shared:domain:jvmTest :shared:tracking:jvmTest :shared:metrics:jvmTest
./gradlew check
```

The fixtures in [tests/gps-fixtures](../tests/gps-fixtures/README.md) pin the
tracking and metric behavior.
