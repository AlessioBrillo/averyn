# TDD-0001: Tracking Engine

- **Status:** draft
- **Version:** 0.1
- **Author:** @AlessioBrillo
- **Date:** 2026-09-26
- **Related:** [ADR-0004](../adr/0004-kotlin-multiplatform-shared-native-ui.md), [ADR-0006](../adr/0006-postgres-postgis-s3-storage.md), [report §4.1, §6–9](../product/reference-report-v0.1.md), [device matrix](../testing/device-matrix.md)

## 1. Summary

The tracking engine records an activity from native location services into a durable on-device store, survives app death, and produces live metrics, a quality report and a GPX export. It is the highest-risk component of the product and the subject of **MVP-0**. This document defines the contract; the PoC validates it on real devices.

## 2. Goals and non-goals

**Goals (MVP-0):** GPS recording in foreground/background/screen-locked; pause/resume; incremental local writes; crash/kill/reboot recovery; live distance/time/pace; GPX export; GPS quality diagnostics; measured battery cost.

**Non-goals (MVP-0):** networking and sync, accounts, BLE/HealthKit/Health Connect, watch, maps UI, auto-pause heuristics beyond simple speed threshold, non-GPS sports.

## 3. Functional requirements

| ID | Requirement |
|---|---|
| F1 | Record location samples at a configurable interval while `RECORDING`, in foreground and background, with the screen locked |
| F2 | Pause and resume; total, moving and paused durations are tracked separately |
| F3 | Every accepted sample is durably written locally **before** it is used for live metrics |
| F4 | After process death, crash or reboot, an interrupted activity is detected on next launch and offered for resume or finish; data recorded up to the last durable write is kept |
| F5 | **No silent data loss:** every dropped/filtered sample is counted with a reason; raw samples are never modified or deleted by filtering |
| F6 | Live metrics: elapsed time, moving time, distance, current/average speed and pace |
| F7 | Export a completed (or recovered) activity as valid GPX 1.1 |
| F8 | A quality report per activity (see §5.4) |
| F9 | Permission changes mid-activity (revoked, downgraded to approximate) are detected and surfaced; the activity moves to a defined state, never silently stops |

## 4. Non-functional requirements

- **Durability:** worst-case loss after hard kill ≤ 1 flush interval (target ≤ 5 s / ≤ 5 samples).
- **Battery:** measured per hour of tracking, per device, recorded in the device matrix; budget set after the first measurements (open question Q5).
- **Memory:** bounded; a 10 h activity must not grow memory with duration (samples stream to disk).
- **Privacy:** the engine never sends data anywhere; diagnostics stay on-device unless the user exports them.
- **Determinism:** the same raw samples always produce the same metrics for a given `algorithm_version`.

## 5. Design

### 5.1 Layering

```mermaid
flowchart TD
  subgraph native["Native (per platform)"]
    LS[Location adapter<br/>CLLocationManager / FusedLocationProvider]
    BG[Background execution<br/>iOS background modes / Android foreground service]
    ST[Local store driver]
  end
  subgraph shared["shared/ (Kotlin Multiplatform)"]
    SM[ActivityState machine]
    VAL[Validation & normalization]
    QA[Quality assessment]
    LM[Live metrics]
    EXP[GPX export]
  end
  LS -->|raw samples| VAL --> QA --> ST
  ST --> LM --> UI[UI / audio]
  SM --- BG
  ST --> EXP
```

Rule: everything in `shared/` is pure Kotlin and unit-testable on the JVM against fixtures. Native code is thin: it converts platform callbacks to `LocationSample` and drives the state machine.

### 5.2 State machine

States: `IDLE, PREPARING, RECORDING, PAUSED, STOPPING, COMPLETED, FAILED`. Implemented in `shared/domain` (`ActivityState.transition`).

```mermaid
stateDiagram-v2
  [*] --> IDLE
  IDLE --> PREPARING: start
  PREPARING --> RECORDING: gps ready
  PREPARING --> IDLE: cancel
  PREPARING --> FAILED: permission denied / no provider
  RECORDING --> PAUSED: pause
  PAUSED --> RECORDING: resume
  RECORDING --> STOPPING: stop
  PAUSED --> STOPPING: stop
  RECORDING --> FAILED: unrecoverable error
  PAUSED --> FAILED: unrecoverable error
  STOPPING --> COMPLETED: finalized
  STOPPING --> FAILED: finalize error
  COMPLETED --> [*]
  FAILED --> [*]
```

`FAILED` never means "data discarded": a failed activity keeps its durable raw samples and can be exported or recovered.

### 5.3 Sample model (proposal v0)

Canonical `LocationSample` (in `shared/domain`):

| Field | Type | Notes |
|---|---|---|
| `timeMs` | Long | Wall-clock, UTC epoch ms. Can jump (NTP, user change) — never used alone for ordering |
| `elapsedRealtimeMs` | Long | Monotonic since boot (Android `elapsedRealtimeNanos`, iOS system uptime). Used for ordering and duration |
| `latitude`, `longitude` | Double | WGS84 degrees |
| `horizontalAccuracyM` | Double | Platform-reported 1σ/68% radius; ≥ 0 |
| `altitudeM` | Double? | Meters, source (baro/GPS) recorded in metadata later |
| `speedMps` | Double? | Platform-reported; not trusted blindly |
| `bearingDeg` | Double? | 0–360 |

Sensor channels (HR, cadence, power) are separate streams keyed by time, added in MVP-2, so GPS records stay compact.

### 5.4 Quality assessment

Per-sample flags (a sample can carry several; raw data is preserved): `POOR_ACCURACY`, `DUPLICATE`, `NON_MONOTONIC_TIME`, `SPEED_OUTLIER`, `JUMP`, `ALTITUDE_SPIKE`. Per-activity report: counts per flag, longest gap, sample-interval distribution, % time with accuracy ≤ 20 m, and a summarized grade (`GOOD/FAIR/POOR`). Thresholds start as constants versioned with `algorithm_version` and are tuned from fixtures (Q3).

### 5.5 Local persistence and recovery

- Append-only write path: samples are appended to the local store in small batches (flush by count **or** time, whichever first). Activity metadata records `state`, `startedAt`, `lastFlushAt`.
- On launch, any activity whose state is `RECORDING`/`PAUSED`/`STOPPING` without a clean end is **interrupted**: recover, show the user what was kept, let them resume or finish.
- Background execution: iOS uses the location background mode with `allowsBackgroundLocationUpdates` and pause-safe settings; Android uses a foreground service of type `location` with a persistent notification. Vendor battery-killer behavior is documented per device in the matrix.
- Storage engine is an open question (Q2); the interface is `ActivityStore` in `shared/tracking` so engines can be compared in the PoC.

### 5.6 Pause semantics

- Manual pause stops sample use for distance/moving time; the engine may keep GPS warm (configurable) to speed up resume.
- Auto-pause (speed below threshold for N s) is a *classification* of time, applied at metric time, not a discarded sample.
- Durations: `total = end − start`, `paused` = sum of paused intervals, `moving = total − paused − stationary`.

## 6. Test strategy

- **Unit (JVM):** state machine (all legal and illegal transitions), metric functions, quality flags — against `tests/gps-fixtures/`.
- **Fixtures** (report §6.4): straight line, tight curves, full pause, resume, intermittent signal, outliers, variable rate, slow/fast movement, noisy altitude, long activity, duplicate/non-monotonic timestamps, missing sensors. Format in [tests/gps-fixtures/README.md](../../tests/gps-fixtures/README.md).
- **Instrumented (real devices):** background, lock screen, force-kill, reboot, permission change, low battery, network loss — logged in the [device matrix](../testing/device-matrix.md). Emulators are not sufficient for this component.
- **Battery:** ≥ 1 h controlled runs per device, same route/conditions, recorded in the matrix.

## 7. Rollout

PoC apps are internal builds (Android debug APK, iOS via a personal dev team). No user data leaves the device in MVP-0.

## 8. Risks and mitigations

| Risk | Mitigation |
|---|---|
| OEM battery optimizations kill Android service | Foreground service + guidance flow; per-vendor results in the matrix |
| iOS suspends/terminates the app | Correct background mode + significant-change/relaunch handling; recovery is a first-class flow |
| KMP↔Swift interop friction on hot path | Keep hot path in native adapter → shared calls coarse-grained (batches); measure |
| Filtering hides real data | Filtering only flags; raw always stored; export can choose raw or cleaned |
| Can't test iOS locally (Windows dev machine) | macOS CI runner + real iPhone via Xcode when available; tracked as a project risk |

## 9. Open questions

| # | Question | Produces |
|---|---|---|
| Q1 | Sampling interval and distance filter per sport | ADR + constants |
| Q2 | Local DB: SQLDelight (shared) vs. platform-native (Room / GRDB) | ADR |
| Q3 | Filtering algorithm (threshold rules vs. Kalman/Savitzky–Golay) and thresholds | Metric spec + ADR |
| Q4 | On-disk/raw-blob sample encoding (also used for sync upload) | ADR |
| Q5 | Battery budget per hour by sport | Number in this doc |
| Q6 | Altitude source policy (GPS vs. barometer vs. DEM correction) | ADR |
