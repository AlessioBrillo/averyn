# TDD-0007: Sensor channels — BLE heart rate (bpm + consent-gated RR) end-to-end

- **Status:** implemented
- **Version:** 0.2
- **Author:** @AlessioBrillo
- **Date:** 2026-10-18
- **Related:** [TDD-0001](TDD-0001-tracking-engine.md), [TDD-0002](TDD-0002-activity-sync.md), [ADR-0014](../adr/0014-sensor-integration-contract.md), [ADR-0015](../adr/0015-local-activity-store.md), [ADR-0020](../adr/0020-sensor-raw-channels.md), [ADR-0021](../adr/0021-consent-health-data.md), [docs/metrics/heart-rate.md](../metrics/heart-rate.md), [docs/metrics/rr-quality.md](../metrics/rr-quality.md)

## 1. Summary

MVP-2's first sensor channel: BLE heart rate (standard GATT Heart Rate service `0x180D`, characteristic `0x2A37`) recorded alongside GPS in the activity file, with raw RR intervals kept in a consent-gated sidecar (ADR-0020/0021). `hr-v2` is a versioned, reproducible metric (`rr-quality-v1` is specified but out of this slice, see the non-goals); nothing here touches Synapse-specific code — any standard HR strap works, Synapse included only when its firmware exposes the standard service (ADR-0014, roadmap Synapse track).

## 2. Goals and non-goals

**Goals:** live HR during recording (any sport), HR persisted with the activity, server recomputes HR from raw bytes, consent-gated RR sidecar with revoke/delete, the `hr-v2` diagnostics field, Android + iOS adapters behind one pure-Kotlin interface in `shared/sensors`.

**Non-goals (v1):** `rr-quality-v1` (specified in [rr-quality.md](../metrics/rr-quality.md), computed after launch), RR-to-HRV scores (`hrv-v1` is a later increment), HealthKit/Health Connect, cadence/power, FIT import, Synapse compressed-feature characteristic, zones/time-in-zone (needs a user profile — separate increment), maps/elevation.

## 3. Functional requirements

| ID | Requirement |
|---|---|
| R1 | A `BleHrAdapter` (Android) and a `CoreBluetooth` adapter (iOS) implement one `shared/sensors` `SensorAdapter` interface; only standard GATT is required — no vendor-specific code paths. |
| R2 | HR samples are written to the activity's `.jsonl` as `HR` records (formatVersion 2), flushed before use (F3), counted as `HR_RECORD` in diagnostics; unknown record kinds on old readers are losses, never crashes. |
| R3 | RR intervals from `0x2A37`'s flag-gated field go to the sidecar `<id>.rr.jsonl` only while a valid `rr_recording` consent is cached; fail-closed (no consent → no RR), HR bpm unaffected. |
| R4 | Revoking `rr_recording` stops RR recording immediately, cancels the pending sidecar upload, and deletes already-uploaded RR objects via a `deleted_rr` tombstone (`410` on re-upload); the activity file is untouched. |
| R5 | Server recomputes `hr-v2` from the `.jsonl` `HR` records and stores `avg_hr_bpm`, `max_hr_bpm`, `hr_source` |
| R6 | `replay`/`recover` stay correct with `HR` records interleaved: `live == recovered == replayed` including HR diagnostics counts; `formatVersion 1` files read by a v2 reader and vice versa. |
| R7 | Web detail page shows HR; history/export include consents and RR sidecars. |
| R8 | Device matrix rows S15 (HR vs reference strap) and S16 (consent revocation mid-activity). |

## 4. Non-functional requirements

- **Determinism:** same `.jsonl` bytes → same `hr-v2` for a given algorithm version; metrics computed only by `shared/` code, server-side (TDD-0002 S3).
- **Privacy:** HR is Restricted, RR is Health — coordinates/tokens never in logs; consent record kept as audit, never deleted with the data.
- **Battery:** HR scan/connect is bounded; no continuous scanning while not recording; reconnect backoff.
- **Self-hosting:** no new service; one more object prefix and one Flyway migration.

## 5. Design

### 5.1 `shared/sensors` (new KMP module, pure Kotlin)

```text
dev.averyn.sensors
  HeartRateSample(timeMs, elapsedRealtimeMs, bpm: Int, contact: Boolean?)
  RrInterval(timeMs, elapsedRealtimeMs, rrMs: Int)
  SensorAdapter (interface: start(session), stop(), isAvailable(), consentRequired(): Boolean)
  HeartRateFrameParser: parses Bluetooth 0x2A37 (uint8/uint16 value, contact, energy; RR flag-gated)
```

The native adapter (Android `BluetoothGatt`, iOS `CBPeripheral`) is the only place touching platform BLE; it converts frames to `HeartRateSample`/`RrInterval` and hands them to `shared/sensors`. RR parsing happens in the adapter (native), not in `shared/` (ADR-0004).

### 5.2 Store (ADR-0020)

`ActivityMetadata.formatVersion: Int` (1 = GPS only, 2 = GPS + HR). New `ActivityRecord.HeartRate(sample: HeartRateSample)` stored as `HR <json>` line, appended+flushed like `SAMPLE`. Sidecar `<id>.rr.jsonl` with own META (`formatVersion 1`) and `RrInterval` records; uploaded as object `raw/{ownerId}/{clientActivityId}/rr/{sha256}.jsonl`; tombstone table `deleted_rr(owner_id, client_activity_id)`.

### 5.3 Metrics

`hr-v2`: avg/max/min bpm over RECORDING-state HR samples, coverage % (HR samples / expected intervals), dropouts; no interpolation, no zones. `rr-quality-v1` (not computed yet): counted artifacts only, see its metric spec.

### 5.4 Consent (ADR-0021)

Backend `consents` table + API; device cache fail-closed; adapter checks before starting RR streaming; revocation handled by backend + client in-app UI.

## 6. Test strategy

- **`shared/sensors` (JVM):** `HeartRateFrameParser` against captured `0x2A37` frames (uint8, uint16, contact flag, RR present/absent, truncated tail); `SensorAdapter` contract.
- **`shared/tracking` (JVM):** `replay`/`recover` with interleaved `HR` records; v1 file read by v2 reader (HR lines counted as UNKNOWN_RECORD_TYPE loss); v2 file read by v1 reader (same); `writeGpx` unchanged (HR never in GPX).
- **`shared/metrics` (JVM):** `hr-v2` on synthetic fixtures, deterministic.
- **Backend (Testcontainers):** upload v2 `.jsonl` + RR sidecar → `201`; metrics equal replay; revoked consent → sidecar `410`; unknown `formatVersion` → `422`.
- **Android/iOS:** adapter unit tests (pure parts); real-device run with a strap for S15; revoke consent mid-activity for S16.

## 7. Rollout and migration

- `shared/sensors` added to `settings.gradle.kts` (also `AVERYN_BACKEND_ONLY` so the backend Docker image builds it) and exported by `shared/ios-umbrella`.
- `ActivityMetadata.formatVersion` defaults to 1 when absent (old files); new files written as 2. Flyway `V6` adds `avg_hr_bpm`, `max_hr_bpm`, `hr_source` to `activities`; `V7` adds `deleted_rr`; `V8` adds `consents`.
- Existing local activities upload unchanged (formatVersion 1).

## 8. Risks and mitigations

| Risk | Mitigation |
|---|---|
| Android 14+ background BLE restrictions | Scan/connect while recording (foreground service already active); per-device matrix notes |
| iOS Bluetooth state restoration kills long connections | Reconnect with backoff; gap reported in diagnostics, never fabricated |
| Consent fetch fails offline | Fail-closed for RR; HR bpm still recorded; retry with backoff |
| Server and device HR diverge | One implementation in `shared/`; fixtures identical on both |

## 9. Open questions

| # | Question | Produces | Status |
|---|---|---|---|
| Q1 | `hr-v2` in `DiagnosticsReport.algorithms` as a 5th field, or a separate `sensorVersions` map? | Diagnostics v3 shape | Resolved: a field of `algorithms` (`heartRate`), present only when HR was recorded |
| Q2 | RR sidecar uploaded with the activity or independently after? | Sync flow | Resolved: after the activity object, in the same sync run, and never holding it back (`ActivitySync.uploadRrSidecar`) |
| Q3 | `deleted_rr` tombstone cleaned up after N days? | Retention ADR | Deferred: tombstones are kept until a retention decision; none is needed for launch |

## 10. What is implemented (2026-10-24)

The channel is landed on Android, with the parts that were still missing closed:

- **R1:** the Android `BleHrAdapter` and the iOS `HeartRateMonitor` (CoreBluetooth) both implement the `shared/sensors` `SensorAdapter` interface. Standard GATT only: `0x180D` / `0x2A37`, no vendor-specific code.
- **R2:** new files are written as `formatVersion 2` and the server refuses a format version it does not know.
- **R3:** the sidecar is created with its own META line, from the activity's own metadata, before the first interval; the device reads the server's grants and fails closed.
- **R4:** revoking `rr_recording` deletes the stored sidecars and leaves a `deleted_rr` tombstone; the device deletes its local copy when the upload is refused.
- **R5:** the server recomputes `hr-v2` from the `HR` records.
- **R6:** `replay`/`recover` handle the `HR` records.
- **R7:** the web account page grants and revokes the scopes; the export holds the sidecars and the consents.
- **R8:** the device matrix rows S15 and S16 are still open: they need a real strap and a real device.
- `rr-quality-v1` is specified but not computed; it is not part of this TDD's scope.
- Not covered yet: unit tests of the BLE adapters themselves (the shared parser and the metric are tested), `replay`/`recover` tests with `HR` records interleaved (R6), and the device-matrix rows S15 and S16 on iOS.
