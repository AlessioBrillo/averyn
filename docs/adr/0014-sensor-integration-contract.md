# ADR-0014: Sensor integration contract — standards first, vendor extensions native-only, imported metrics stay imported

- **Status:** accepted
- **Date:** 2026-09-27
- **Deciders:** @AlessioBrillo

## Context and problem

Averyn needs a rule for integrating *any* external sensor (BLE heart-rate straps, Garmin/Wahoo via FIT, Synapse's device, future ones) that doesn't special-case one vendor, keeps `shared/` pure per [ADR-0004](0004-kotlin-multiplatform-shared-native-ui.md), and doesn't let a phone become an unbounded relay for another company's raw biosignal data. The strategy input's Synapse-specific sketch put `BluetoothGatt` and `Context` calls inside `shared/tracking` commonMain code, invented new `shared/metrics` types for cloud-computed scores, and left both the GPS source and the sleep-stage/FIT-field encodings inconsistent across its own documents ([`docs/strategy/review-2026-09-27.md`](../strategy/review-2026-09-27.md) #4, #13, #14, #17). Per your decision, Synapse must also be **one sensor among many, never a dependency of a core feature** ("best client" model).

## Options considered

1. **Vendor-specific adapters reaching into `shared/`** — as sketched. Fast to write once, but breaks the platform-independence rule the first time it needs a platform BLE stack, and couples core modules to one vendor's data shapes.
2. **Standard profiles first, native adapters for extensions, imported data stays imported.** Every BLE sensor is consumed through the standard GATT services it's required to expose (Heart Rate `0x180D`/`0x2A37` including the RR-interval field, Running/Cycling Speed & Cadence, Battery, Device Information); anything vendor-specific (Synapse's compressed-feature characteristic, a proprietary FIT developer field) is read by a **native** adapter (Kotlin/Swift, behind an interface `shared/tracking` defines) and only handed to `shared/` as an already-parsed, plain-data `SensorSample`/import record — never as a live GATT/Context object. Any metric Averyn doesn't compute itself (cloud/vendor-derived) is stored as **imported data** with `source` and an *external* `algorithm_version`, never mixed into Averyn's own versioned `shared/metrics` types.
3. **Only support Garmin/FIT-file import, no live BLE from third-party sensors at all.** Simplest, but drops live HR-during-activity, a table-stakes feature.

## Decision

**Option 2.**

- **Standard profiles are the baseline contract.** Any BLE sensor Averyn talks to live must expose the applicable standard GATT services (Heart Rate, RSC/CSC, Battery, Device Information at minimum); Averyn's generic `BleSensorAdapter` only ever requires these. RR intervals are read from the standard `0x2A37` flag-gated field, never a repurposed `0x2A38` (which is Body Sensor Location, per the review's #2).
- **Vendor extensions are native, opt-in, and behind the same interface.** A vendor-specific adapter (e.g. `SynapseBleAdapter`) lives in the platform app module, implements `shared/tracking`'s adapter interface, and is the only place that touches `BluetoothGatt`/`CBPeripheral`/`Context`. It parses vendor characteristics into plain data classes before crossing into `shared/`. Any vendor frame is versioned (a leading version byte) and fits within `MTU − 3` bytes with an explicit sequence number for reassembly — no raw multi-hundred-sample-per-second streams assumed to fit in one notification (review #3).
- **Cloud/vendor-computed scores are imported, not Averyn metrics.** A `ReadinessScore`, sleep stage, or stress classification computed by Synapse's (or anyone's) inference worker is stored with `source` and the *producing* system's own `algorithm_version` string, displayed with clear provenance, and never retrofitted into a `shared/metrics` `@MetricDefinition`-style Averyn-owned type — Averyn can't reproduce it deterministically from raw samples, which is the whole point of that mechanism (review #14).
- **One canonical encoding, not a document that disagrees with itself.** Sleep stage is a single 5-class enum (`W, N1, N2, N3, REM`), matching FIT-file convention; any FIT developer field is a flat scalar (uint8/uint16/…), never a "struct" field type that doesn't exist in the FIT spec (review #4, #5).
- **Canonical GPS source, explicit dedup rule.** When both the phone and a paired device report GPS for the same activity, the phone's location stream is canonical by default (it's what TDD-0001 already validates); a device-provided track is only used to *fill gaps* the phone missed (e.g. phone GPS off, device kept recording), never merged blindly — this closes the "two independent GPS sources, no reconciliation" gap (review #17). The exact merge algorithm is designed when the first device with its own GPS is actually integrated (tracked in the roadmap), not here.
- **Consent gates the adapter, not just the API.** Per [ADR-0013](0013-health-data-special-category.md), a vendor adapter for health-adjacent data must check the relevant consent scope before it's even allowed to start streaming, not just before the result is displayed.

The concrete contract Synapse's device firmware must meet to be integrable under this ADR is [`docs/integrations/synapse.md`](../integrations/synapse.md).

## Consequences

- Good: adding a second or third sensor vendor later doesn't touch `shared/`'s architecture rule again — it's already vendor-neutral.
- Good: Averyn's metric versioning guarantee (CLAUDE.md: "a behavior change = new version") stays true, because nothing not reproducible by Averyn's own code is ever labeled as an Averyn metric.
- Cost: vendor-specific richness (e.g. Synapse's extra biosignals) only shows up through explicit "imported data" UI, not seamlessly blended with native metrics — an intentional trade for provenance honesty.
- Follow-up: the actual `SensorSample` shared data class and native adapter interface are designed in TDD form when MVP-2 (BLE/HealthKit/Health Connect) starts, per the roadmap — this ADR sets the rule, not the Kotlin signatures.

## Revisit when

A second real sensor vendor integration exposes a requirement this contract doesn't cover, or the GPS dedup heuristic above proves wrong in practice (then it gets its own ADR, not a silent edit here).
