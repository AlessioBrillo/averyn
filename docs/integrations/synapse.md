# Synapse integration contract

Status: v0.3 — draft (2026-10-29). What Averyn requires from, and does with, a Synapse device, under [ADR-0010](../adr/0010-ecosystem-boundary.md), [ADR-0011](../adr/0011-identity-oidc.md), [ADR-0014](../adr/0014-sensor-integration-contract.md) and [ADR-0026](../adr/0026-averyn-synapse-link.md). It supersedes the device-side specifics in [`docs/strategy/`](../strategy/README.md) (corrections in [`review-2026-09-27.md`](../strategy/review-2026-09-27.md)). v0.2 replaced the byte-level tables of v0.1: the device repository owns them. v0.3 adds the roles of the two products, the way a contract change travels and the pairing experience.

Level L0 needs no code. Level L1 is scheduled in the [roadmap](../product/roadmap.md#synapse-track) as a beta and is the first item on the cut line; its pure core is in place (section 1b), the native adapters, the sidecar and the server side wait for a prototype that produces real frames and for the approval of [ADR-0026](../adr/0026-averyn-synapse-link.md).

## Roles of the two products

| | Averyn | Synapse |
|---|---|---|
| What it is | The free app (iOS, Android, web) and the cloud | A band that talks BLE, plus a research SDK |
| Owns | Accounts, consent, activities, the only system of record | The device, its firmware, the byte-level contract |
| Pays for | The managed cloud, from donations and from the margin of the bands sold ([business model](../product/business-model.md)) | The band, sold at a price that includes that margin |
| Never does | Call the Synapse data-plane API, subscribe to the raw research streams | Run a consumer cloud or hold user accounts; the provisioning path to a cloud in the firmware is not used by the product |

Data path: band → BLE → phone (Averyn) → sync → Averyn cloud. Nothing else. The pages and calls that the Synapse repository's own interface document once assumed (an HTTP pull by Averyn, `/synapse/*` pages) are dropped: that document is rewritten in the device repository ([#72](https://github.com/AlessioBrillo/averyn/issues/72)).

## Principle

A Synapse band is one BLE sensor among others. Averyn's generic sensor support (standard Heart Rate, Battery, Device Information, FIT import, HealthKit, Health Connect) works with **zero** Synapse-specific code. Everything below the first level is the additional, optional layer for the band's extra signals, and it never gates a core feature.

## 1. The pinned contract

The contract is owned by the device repository (`SYNAPSE-24`): `contracts/gatt_v1.json` (GATT services, characteristics and the feature-frame layout) and `contracts/fit_developer_fields.json` (flat scalar FIT developer fields). Averyn pins one version of each:

| What | Pinned |
|---|---|
| Contract version | GATT v1, FIT developer fields 1.0 |
| Source | `SYNAPSE-24` `master` at `f9ceb45` (the contract was merged there) |
| Vendored copy and sha256 | `shared/sensors/src/jvmTest/resources/synapse/gatt_v1.json`, sha256 `0c6b9e38…88a9`, checked by `SynapseFeatureFrameVectorsTest` (a changed contract fails the build until it is re-pinned on purpose) |
| Golden vectors | `feature_frame_v1_vectors.json`, same folder: frame bytes → expected values, 9 of them (every field present; what the current firmware sends; everything not available; a wrapping counter and a negative temperature; out-of-range values; a longer frame; an unknown version; a cut frame; nothing). Generated with Python's `struct` from the packed layout of `synapse_feature_stream_t`: the device SDK decodes only the raw research streams, so there is no decoder there to take vectors from |

**How a contract change travels.** (1) A pull request in the device repository changes `contracts/` and bumps the version, regenerates the vectors with the SDK encoder and updates its own checks; (2) a pull request here updates the vendored copy, the sha256 in the test and the parser, with the vectors as tests; (3) neither merges alone — the second is opened against the first and both are merged the same day. Until the vectors come from the SDK encoder, the ones here are generated from the packed struct and are labelled as such. A frame whose version this parser does not know is counted as a loss and skipped, never interpreted (ADR-0014).

### 1b. What is in place

`parseSynapseFeatureFrame` (`shared/sensors`) decodes the feature frame: pure, no platform types, never throws on a short or foreign frame, every field the band marks "not available" is **null** and never zero, a value out of its range is null too. `SynapseSequenceTracker` counts the frames the link lost from the 16-bit counter (it wraps; a repeat or an old frame is counted apart and does not move the counter back; a counter that restarts, because the band rebooted, is counted as a restart and followed — it is not tens of thousands of lost frames). The service and characteristic UUIDs are constants next to it, tested against the vendored contract.

**A discrepancy to fix in the device repository:** the packed struct is **23 bytes** (1 + 2 + 2 + 2 + 8 × 1 + 2 + 2 + 2 + 2), while the comment in `ble_gatt_server.h` says 26. The parser follows the struct, accepts a longer frame of the same version (reading the fields it knows) and reports a shorter one as truncated; the device repository should correct the comment and publish the size in the contract ([#72](https://github.com/AlessioBrillo/averyn/issues/72)).

What Averyn relies on, without copying the tables:

- **Standard services** `0x180D` (Heart Rate, RR in the flag-gated field of `0x2A37`), `0x180F` (Battery), `0x180A` (Device Information). These are level L0.
- **One consumer service** with a feature-stream characteristic notified at 1 Hz, a versioned frame with a `sequence`, and a per-field "not available" sentinel. The current firmware fills only heart rate, the PPG quality index, the motion-artifact probability and the stress class; RMSSD, SDNN and RR are always "not available".
- **A research service** with raw ECG/PPG/IMU streams. Averyn never subscribes to it.
- **FIT developer fields**, flat scalars. The stress enum is `0 = baseline, 1 = stress, 2 = artifact, 3 = unknown`.

### Pairing experience (L0 and L1)

The band is found by the generic strap scan, with the name it advertises, and works as a strap with no setup (L0). Only when the band offers the feature service does the app offer the extra signals, in a separate step that explains in wellness words what is read and asks for the two consents (`signal_quality`, `stress_monitoring`) one by one; declining leaves the band working as a strap. Data produced by the band is labelled "via Synapse" wherever it is shown.

## 2. Levels

See [ADR-0026](../adr/0026-averyn-synapse-link.md) for the table and the rules of L1 (sentinels, loss accounting, timestamps, consent scopes `signal_quality` and `stress_monitoring`, the sidecar, provenance, device binding).

## 3. Data ownership and storage

Averyn stores the device record, the session, the raw file and the consent grant ([ADR-0010](../adr/0010-ecosystem-boundary.md)). Data from a Synapse device enters as **imported data**:

- `source: "synapse-device"` (live frame) or `"synapse-worker"` (a worker result, after launch).
- `algorithm_version`: the producer's own string, passed through verbatim and never coerced into an Averyn `algorithm_version`: Averyn did not compute it and cannot reproduce it deterministically, so it is not a `shared/metrics` type ([ADR-0014](../adr/0014-sensor-integration-contract.md)).
- Displayed with visible provenance ("via Synapse"), never blended silently into Averyn's own charts.

A Synapse inference worker never has its own user database: it receives a job (job id, a reference to a file Averyn stored, a computation type and version, no user identifier) and returns a result keyed to that job id. That is level L3, after launch.

## 4. Auth

No shared service-account token that can query any user's data ([ADR-0011](../adr/0011-identity-oidc.md)). For L3, Averyn mints a short-lived, job-scoped credential per dispatch, and a result is accepted only against that job id. Device provisioning, when it exists, uses a CSR flow: the device generates its key pair and a CSR; a private key is never generated elsewhere and sent to it.

## 5. Consent

Per [ADR-0013](../adr/0013-health-data-special-category.md) and [ADR-0021](../adr/0021-consent-health-data.md), each scope is a separate, revocable grant, checked by the native adapter before it subscribes, and again while it records. Scopes for the Synapse layer: `signal_quality`, `stress_monitoring` at launch; `hrv_analysis`, `sleep_staging`, `raw_data_export`, `research_sharing` are reserved for later levels.

## 6. Canonical GPS source

Per [ADR-0014](../adr/0014-sensor-integration-contract.md): the phone's location stream is canonical. If a device has its own GPS, its track only fills gaps the phone missed; activities are never silently merged from two tracks.

## Open questions

- Whether the firmware will send the real RR in `0x2A37` and fill RMSSD/SDNN (a request to the device repository, tracked in ADR-0026). Until then L1 shows signal quality, motion artifact and the stress class only.
- Whether the sleep-stage field is ever populated without EEG input, and what accuracy claim is honest to show: a product and legal question, not a technical one.
- The worker job/result schema (queue technology, payload): designed when L3 starts.
