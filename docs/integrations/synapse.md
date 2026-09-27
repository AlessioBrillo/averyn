# Synapse integration contract

Status: v0.1 — draft. This is what Averyn requires from Synapse's device firmware and inference worker to be integrable under [ADR-0010](../adr/0010-ecosystem-boundary.md), [ADR-0011](../adr/0011-identity-oidc.md) and [ADR-0014](../adr/0014-sensor-integration-contract.md). It supersedes the device-side specifics in [`docs/strategy/`](../strategy/README.md) (see the corrections in [`review-2026-09-27.md`](../strategy/review-2026-09-27.md)). Nothing here is implemented yet — no Synapse code exists in Averyn before [MVP-2](../product/roadmap.md).

The Synapse Band does not exist as hardware today; the real repo (`SYNAPSE-24`) is a lab research rig with no GPS and no BLE service meeting this contract yet. This document describes the target Averyn integrates against, for whenever that firmware exists — it is not a claim about current firmware state.

## Principle

The Synapse Band is one BLE sensor among others (per your "best client" decision). Averyn's generic sensor support (standard Heart Rate/RSC/CSC/Battery/Device Information, FIT import, HealthKit, Health Connect) works with **zero** Synapse-specific code. Everything below is the *additional*, optional layer for Synapse's extra biosignals.

## 1. BLE GATT

- **Standard services are mandatory** and are all Averyn's generic adapter needs: Heart Rate (`0x180D`) with RR intervals carried in `0x2A37`'s flag-gated field (not `0x2A38`, which is Body Sensor Location), Battery (`0x180F`), Device Information (`0x180A`). Running/Cycling Speed & Cadence (`0x1814`/`0x1816`) where applicable.
- **One Synapse-specific service**, identified by a properly-formed, randomly generated 128-bit UUID — not the Nordic UART Service UUIDs the current `SYNAPSE-24` firmware happens to reuse, and not the strategy input's malformed 6-group string.
- **No raw multi-hundred-Hz biosignal streams over BLE to a phone.** ECG/PPG/IMU raw samples stay on-device or go over LSL/USB to a researcher's machine — that's the device SDK's job, not Averyn's. The one Synapse-specific characteristic Averyn reads is a **compressed feature frame**, notified at ≤10 Hz, containing whatever of {RMSSD, SDNN, PPG/ECG signal-quality index, motion-artifact probability, stress-triage class} the firmware computes.
- **Frame format:** `[version: u8][sequence: u16 LE][payload...]`, payload fields little-endian, total frame ≤ `MTU − 3` bytes. A version bump is required for any field addition/reordering; old parsers must be able to at least detect and skip a frame of a version they don't understand, never misinterpret it.
- Stress-triage class: `0 = baseline, 1 = stress, 2 = artifact` (matches the strategy input's own enum — the one thing file 04 got right and consistently).

## 2. FIT import

Flat scalar developer-data fields only (FIT has no struct field type — see review #5). Canonical table, sleep stage using the 5-class enum consistently (not the 4-class one in the strategy's file 02):

| Field name | Type | Scale | Units | Description |
|---|---|---|---|---|
| `synapse_stress` | uint8 | 1 | enum | `0=baseline, 1=stress, 2=artifact` |
| `synapse_sqi` | uint8 | 1/255 | ratio | Signal quality index |
| `synapse_map` | uint8 | 1/255 | ratio | Motion artifact probability |
| `synapse_sleep_stage` | uint8 | 1 | enum | `0=W, 1=N1, 2=N2, 3=N3, 4=REM` — **only present if the producing pipeline actually has an EEG input**; a PPG/IMU-only heuristic must use a differently-named field and say so in its UI label, never overload this one (review #6) |
| `synapse_rmssd` | uint16 | 1 | ms | RMSSD, 5-minute window |
| `synapse_sdnn` | uint16 | 1 | ms | SDNN, 5-minute window |
| `synapse_lf_hf` | uint16 | 1/100 | ratio | LF/HF × 100 |

## 3. Data ownership and storage

Per [ADR-0010](../adr/0010-ecosystem-boundary.md): Averyn stores the device record, the session, the raw file and the consent grant. A Synapse inference worker never has its own user database — it receives a job (job id, a reference to a file Averyn already stored, a computation type + version, no direct user identifier) and returns a result keyed to that job id. Averyn attaches the result to the user's activity as **imported data**:

- `source: "synapse-worker"`
- `algorithm_version`: the *worker's own* version string (e.g. `"sleep-heuristic-ppg-v1"`), passed through verbatim, never coerced into an Averyn `algorithm_version` — Averyn didn't compute it and can't reproduce it deterministically, so it's not a `shared/metrics` type (ADR-0014).
- Displayed with visible provenance ("via Synapse", not blended silently into Averyn's own charts).

## 4. Auth

Per [ADR-0011](../adr/0011-identity-oidc.md): no shared service-account token that can query any user's data. Averyn mints a short-lived, job-scoped credential per dispatch; the worker's response is only accepted back against that same job id. Device provisioning uses a CSR flow (device generates its keypair and a CSR; Averyn/CA returns a signed cert) — never a private key generated off-device and transmitted to it (review #12).

## 5. Consent

Per [ADR-0013](../adr/0013-health-data-special-category.md): each scope (`hrv_analysis`, `sleep_staging`, `stress_monitoring`, `signal_quality`, `raw_data_export`, `research_sharing`) is a separate, revocable grant, checked by the native BLE adapter before it starts streaming Synapse-specific characteristics — not only by the API serving the result.

## 6. Canonical GPS source

Per [ADR-0014](../adr/0014-sensor-integration-contract.md): the phone's location stream is canonical. If a future Synapse hardware revision adds its own GPS, its track only fills gaps the phone missed — activities are never silently merged from two independent tracks.

## Open questions

- Exact compressed-feature frame field list and rates — set when a firmware revision exists that meets §1's constraints, not before.
- Worker job/result schema (queue technology, payload shape) — designed at MVP-2 start, tracked in [`docs/product/roadmap.md`](../product/roadmap.md).
- Whether the sleep-stage field is ever populated without EEG input, and what accuracy claim (if any) is honest to show — a product + legal question, not a technical one.
