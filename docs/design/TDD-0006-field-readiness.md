# TDD-0006: Field Readiness

- **Status:** implemented
- **Version:** 0.1
- **Author:** @AlessioBrillo
- **Date:** 2026-10-15
- **Related:** [TDD-0001](TDD-0001-tracking-engine.md), [TDD-0002](TDD-0002-activity-sync.md), [TDD-0004](TDD-0004-data-deletion-and-export.md), [ADR-0008](../adr/0008-project-codename.md), [ADR-0015](../adr/0015-local-activity-store.md), [ADR-0019](../adr/0019-crash-reporting-and-tester-builds.md), [device matrix](../testing/device-matrix.md)

## 1. Summary

MVP-0 and MVP-1 are implemented but unproven: the device matrix is empty. A code and docs audit (2026-10-15) found defects that would make a real-device campaign fail or produce wrong numbers, and tooling gaps that would stop a tester from producing evidence. This TDD fixes them so that the campaign (S1–S14) measures the product, not the bugs.

## 2. Goals and non-goals

**Goals:** a recovered activity reports the same numbers live, on the phone after a relaunch and on the backend; recovery after kill and after reboot works without user action where the OS allows it and is measurable; GPX export is complete and schema-valid; a tester can export GPX, diagnostics and the raw file of any past activity; both apps can be installed by testers through the store test tracks; the sync path survives the failures S12–S14 provoke.

**Non-goals:** new product features (MVP-2), changes to the metric algorithms, BLE sensors, a crash-reporting SDK ([ADR-0019](../adr/0019-crash-reporting-and-tester-builds.md)), backend-for-frontend, encryption at rest.

## 3. Functional requirements

| ID | Requirement |
|---|---|
| R1 | After a process kill the recording resumes by itself (Android: service restart; iOS: relaunch by the OS), with a RECOVERED event and a visible notification; the next app start explains what happened |
| R2 | After a reboot the same state is reached on the next user-visible launch (Android posts a "tap to resume" notification; iOS relaunches on significant-change); time and distance continue correctly across the boot |
| R3 | `elapsedRealtimeMs` is continuous across a reboot: the recorder and `replay()` apply the same offset, so a recovered activity is not flagged `NON_MONOTONIC_TIME` and has no negative pause |
| R4 | For any activity, live numbers, numbers after `recover()` and `replay()` results are equal, including recovery from PAUSED/STOPPING and "Finish now" |
| R5 | A GPX export is complete (flushed), uses plain decimal numbers and validates against the GPX 1.1 schema |
| R6 | Every dropped input is counted with a reason: samples with no active activity, samples that violate the model |
| R7 | Diagnostics (`diagnostics-v2`) contain what the matrix needs: per-recovery gaps, the event timeline with reasons, algorithm versions, permission and power state, real device model, build id |
| R8 | The apps list past activities and export GPX, diagnostics and the raw file of each; the exports survive a restart |
| R9 | Android requests precise location, notification permission and explains battery optimisation; a reduced-accuracy grant on either platform moves the activity to a defined state (F9) |
| R10 | Tester builds are distributed through Play internal testing and TestFlight; each build identifies itself (version, commit) in diagnostics |
| R11 | An upload cannot recreate data of an account or activity deleted meanwhile; an IdP outage cannot stall authenticated requests; the sync client backs off on 429/503 |

## 4. Non-functional requirements

- **Determinism (TDD-0001 §4):** R3 and R4 are one rule implemented in one place (`ingestSample`/`replay` already share the sample path); no second copy for recovery.
- **Immutability:** nothing is rewritten in the store; the offset is data in a new event field.
- **Battery:** diagnostics must not perturb what they measure: no per-second work proportional to activity length while recording (the sync-status poll reparses the whole file today).
- **Privacy:** no new data class. Diagnostics and exports stay user-initiated; no coordinates in diagnostics.

## 5. Design

### 5.1 Auto-resume (R1, R2; replaces the "offer on next start" half of TDD-0001 F4)

```text
Android  kill / OOM       START_STICKY -> onStartCommand(intent == null)
                          -> store.interrupted() -> recorder.recover() -> back to RECORDING (or stays PAUSED)
Android  reboot           BOOT_COMPLETED receiver -> notification "Activity interrupted, tap to resume"
                          (Android 15 forbids starting a location foreground service from boot)
iOS      kill / reboot    significant-change monitoring relaunches the app (UIApplication.LaunchOptionsKey.location)
                          -> same recover() path
fallback                  anything not resumable automatically (permission lost, GPS off, no relaunch,
                          or an interruption older than 30 minutes): a notification and, on the next
                          app start, the resume-or-finish dialog, exactly as TDD-0001 F4 before
```

"Automatically" is bounded: the interruption must be recent (`AUTO_RESUME_MAX_GAP_MS`, 30 min of wall clock since the last sample or event) and precise location granted. A person who reopens the app three days later decides whether those days belong to the activity. A kill during STOPPING is always finalized without asking (no GPS involved). Older interrupted activities, when several exist, are finished at their last known time so none is left orphaned; the newest is the one resumed.

`recover()` already rebuilds the recorder from the store and logs `RECOVERED`. What changes is who calls it and when. State machine unchanged: no new state, `ActivityState` and the TDD-0001 diagram stay in sync. A service restart with nothing to recover stops itself; nothing may call `fail()` from `IDLE`.

Resuming from PAUSED stays PAUSED (the user paused; GPS keeps feeding samples that are stored and flagged, as today).

### 5.2 Clock continuity (R2, R3)

`elapsedRealtime` restarts at boot. `StateEvent` gets an optional `clockOffsetMs` (default 0, absent in old files and when no reboot happened), meaningful on `RECOVERED` events: the **cumulative** offset in force from that event on. Effective time of any raw value is `raw + offset`. `ActivityStore` also accepts NaN/Infinity in optional fields (`allowSpecialFloatingPointValues`), so an odd platform value is stored as reported instead of crashing the recording.

At `recover()`, with `prev` the cumulative offset replayed from the file, `lastEff` the last known effective time and Δwall the wall-clock difference since then:

```text
candidate = nowRaw + prev
reboot    = candidate < lastEff  OR  |candidate − (lastEff + max(0, Δwall))| > 60 s
offset    = reboot ? lastEff + max(0, Δwall) − nowRaw : prev
```

The recorder applies `offset` to every sample and event it processes afterwards; `replay()` maintains the same running offset. The gap is real time, so it stays inside elapsed time; the leg across it is classified by implied speed as today (distance-v1, time-v1 unchanged).

Known ceiling: the wall-clock consistency check can misfire when the user changes the system time by more than 60 s during the interruption; the result is still monotonic and bounded by the wall-clock gap.

### 5.3 Replay semantics of `RECOVERED` (R4)

- A `RECOVERED` event whose state equals the previous state is a **marker**: it counts as a recovery, it does not start or end a pause.
- An unfinished activity is never closed at the time of the marker. "Finish now" closes at the last known **activity** time (sample or non-marker event), in the recorder and in `replay()` alike.
- `recover()` on STOPPING finalizes at the last known activity time, as before, and logs `COMPLETED` at that time rather than at "now".

Tests assert live == recovered == replayed for: clean run, recovery while RECORDING, PAUSED and STOPPING, "Finish now" while PAUSED, reboot, two recoveries.

### 5.4 GPX (R5)

- `writeGpx` flushes the sink before returning, so every caller (Android SAF stream, iOS file path) is complete. The iOS helper already closed its sink.
- Coordinates and elevation are written as plain decimals (`BigDecimal`-style formatting, never `1.0E-4`).
- Tests write through a file-backed sink and validate with the GPX 1.1 XSD (`tests/gps-fixtures/schema/`, JVM `javax.xml.validation`).
- Two fixes in the same second keep the same `<time>`; GPX has no sub-second field requirement. No change.

### 5.5 Diagnostics v2 (R6, R7)

Additive over `diagnostics-v1`; `formatVersion` becomes 2.

| Field | Meaning |
|---|---|
| `recoveryGaps[]` | per recovery: `lastSampleBeforeMs`, `recoveredAtMs`, `firstSampleAfterMs?`, `gapMs`, `rebootDetected` (the `recoveries` count stays) |
| `events[]` | state timeline: effective ms, wall ms, state, reason |
| `algorithms` | `distance-v1`, `time-v1`, `speed-v1`, `quality-v1` |
| `stationaryMs`, `endedAtMs` | previously missing |
| `droppedInputs` | by reason: `NO_ACTIVE_ACTIVITY`, `INVALID_SAMPLE`, plus the store losses |
| `DeviceInfo` | manufacturer, hardware model (`utsname` on iOS), OS, app version, **build/commit**, permission level (precise/approximate, always/when-in-use), power-saver on, battery-optimisation exempt (Android). Every field is passed explicitly by the native caller (Swift cannot see Kotlin default arguments) |

A FAILED activity also gets a report (the `ponytail:` in `ActivityRecorder.fail`).

### 5.6 History and export (R8)

Both apps get a minimal list of local activities (`ActivityStore.list()`): state, sport, start, sync status. Per row: export GPX, diagnostics JSON, raw `.jsonl` (the file ADR-0015 defines, byte-for-byte what sync uploads). Android uses the Storage Access Framework off the main thread; iOS shares files from Application Support (not `tmp`). Status refresh runs while the screen is visible only, and reads a cached last state instead of reparsing the file.

### 5.7 Permissions (R9)

Android requests `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` together and `POST_NOTIFICATIONS`; "approximate" shows why precise is needed and does not start. A guidance screen opens the system battery-optimisation settings (no direct-exemption permission: Play policy). iOS checks `accuracyAuthorization`; a reduction mid-activity pauses with `PERMISSION_DOWNGRADED`. "Waiting for GPS" has a Cancel (PREPARING → IDLE is already a legal transition and is in the TDD-0001 diagram; `ActivityRecorder.cancel()` is its API and removes the empty activity file). No automatic timeout: a silent give-up would be a silent loss.

### 5.8 Tester builds (R10)

See [ADR-0019](../adr/0019-crash-reporting-and-tester-builds.md). `versionCode` = CI run number, `versionName` = tag, the commit SHA in `DeviceInfo`. Display name "Averyn Beta"; application id and bundle id stay `dev.averyn.app` ([ADR-0008](../adr/0008-project-codename.md)). The release workflow (`.github/workflows/release.yml`) is manual and builds the signed Android bundle from repository secrets; the Play internal and TestFlight uploads are manual steps, written down in [tester builds](../deployment/tester-builds.md). A CI job for the iOS upload is deliberately not written: it cannot be tested without the signing material.

### 5.9 Backend and sync (R11)

- The activity INSERT is conditional: no row for an owner with `deleted_at`, none for a tombstoned `(owner, client_activity_id)`; the object put follows the same check and is removed when the insert is refused. One statement closes both races found in the audit.
- JWKS fetches get connect/read timeouts; the key lookup no longer holds a global lock across the network call.
- `shared/sync`: 429 and 503 stop the pass and honour `Retry-After`; 403 and 410 are distinct permanent states (`ACCOUNT_DELETED`, `DELETED_ON_SERVER`); a running upload is not replaced by a new enqueue (`KEEP`).
- Export validates every raw object exists before sending the first byte; a failure becomes a 5xx, never a 200 with a corrupt archive.
- The upload → list → track → export path is covered against real PostGIS and SeaweedFS by the backend tests (Testcontainers, a stub identity provider). An end-to-end job against the real Zitadel on the Compose stack is **not built** (Q3).

## 6. Test strategy

- **JVM:** live == recovered == replayed (§5.3), reboot offset (§5.2) including double reboot and old files without the field, GPX through a file sink + XSD, diagnostics v2 contents and no coordinates, dropped inputs counted.
- **Backend (Testcontainers):** two threads, delete vs upload; account deletion vs upload; stalled JWKS endpoint.
- **Android:** unit tests for the pure parts (permission state, resume decision); sticky-restart and reboot flows on an emulator with `adb shell am kill` / `adb reboot`.
- **iOS:** CI build; behaviour verified only on devices ([device matrix](../testing/device-matrix.md)).
- **E2E:** the Compose job in §5.9.

## 7. Rollout

Seven stacked PRs, merged bottom first (docs → tracking → Android → iOS → backend/sync → distribution). The metric algorithms keep their versions: this is a bug fix against the existing specs, no stored data exists outside development, so there is nothing to recompute. Documented in `docs/metrics/*.md` as a clarification, not a new version.

## 8. Risks and mitigations

| Risk | Mitigation |
|---|---|
| Android 14+ rejects a background-started location service (`ForegroundServiceStartNotAllowedException`) | Catch it, post the "tap to resume" notification, fall back to the F4 prompt; confirmed only on a device (S5) |
| iOS does not relaunch after the user force-quits from the app switcher | OS behaviour: documented; the next launch recovers (F4); matrix S5 records it per device |
| Offset heuristic misfires on a clock change | Bounded and monotonic by construction (§5.2); the diagnostics record `rebootDetected` so a wrong call is visible |
| No Mac on the CI side for TestFlight signing issues | First tag run is a dry run; the Xcode/App Store Connect setup is a user prerequisite |

## 9. Open questions

| # | Question | Produces | Status |
|---|---|---|---|
| Q1 | Does auto-resume after a swipe-kill behave the same on aggressive OEMs (Xiaomi, OnePlus)? | Matrix rows + guidance text | Open — needs devices |
| Q2 | Final public name and store listing | ADR (supersedes interim part of 0008) | Open — before any public release |
| Q3 | End-to-end CI job with real Zitadel tokens (machine-user token → `PUT` → list → track → export) | CI job | Open — needs a minting recipe that is stable in CI; the Compose smoke tests only assert `401`s today |
| Q4 | Real-device campaign S1–S14 on ≥ 2 Android and ≥ 2 iOS devices | Rows in the device matrix | Open — the exit criteria of MVP-0 and MVP-1 |
