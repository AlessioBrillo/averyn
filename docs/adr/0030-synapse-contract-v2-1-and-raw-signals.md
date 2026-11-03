# ADR-0030: Synapse contract v2.1, and the raw signals shown only in Averyn (level L1R)

- **Status:** proposed (2026-11-02)
- **Date:** 2026-11-02
- **Deciders:** @AlessioBrillo
- **Amends:** [ADR-0026](0026-averyn-synapse-link.md) (the line "the raw research service is never subscribed to by Averyn" and the table of levels). It does not touch ADR-0014's rule that Averyn never calls the Synapse data-plane API.

## Context and problem

Two things changed after [ADR-0026](0026-averyn-synapse-link.md).

1. **The device repository wrote a consumer contract v2** (new characteristics next to the frozen v1: a feature frame with a version, flags and the RR in 1/1024 s; a status record; a stream-metadata characteristic; a control point). Nobody pinned it and no firmware has run, so it can still be corrected for free. The audit of both repositories found that v2 as first written was not a safe base for Averyn: it dropped the motion-artifact probability that L1 shows (v1 `ppg_map`), the `flags` bits had two different orders in the feature frame and in the status record, `stress_level` and `sport_type` had no "not available" value, the unit comments of the generated JSON were lost (a gyroscope travels in mrad/s but a screen shows °/s), and its golden vectors carried the RR in milliseconds. Those are fixed in the device repository as **contract 2.1** (same wire version, same four characteristics, `motion_artifact` appended so no 2.0 offset moves).
2. **The owner decided that every view and analysis of what the band measures lives in Averyn**, and that the device repository keeps only firmware, contracts and tests. ADR-0026 forbids Averyn to subscribe to the raw research service (ECG 500 Hz, PPG, IMU), which was the right call while the only viewer for it was a research tool. If the raw signals are to be seen in Averyn at all, the rule has to change explicitly, with its consent and its limits.

## Options considered

1. **Averyn shows derived values only (L1); raw stays in the device repository's viewer.** Keeps ADR-0026 as it is. Rejected: it contradicts the decision that the device repository has no viewer, and it leaves the user with no way to see why a quality index is low.
2. **Averyn shows derived values (L1) and, behind its own consent, the raw signals (L1R).** One viewer, one consent model, one storage rule. Cost: a new scope, a larger sidecar, a new screen.
3. **Raw always on, with the L1 consents.** Rejected: the raw ECG is the most identifying stream the band has, and a consent for "signal quality" does not cover it.

## Decision

**Option 2.**

**Pin.** Averyn re-pins from GATT v1 to the device repository's `contracts/gatt_v2.json` **2.1**, and adds `contracts/raw_frame_v1.json` (the header, stream ids, sample formats and the rules of the raw frame). Both are vendored with their sha256 in a test, next to the golden vectors the device SDK generates (`consumer_v2.json` for the feature and status records, `raw_frame_v1.json` for raw frames, valid and invalid). v1 stays frozen in the device repository; an old band that only speaks v1 is read by the existing v1 parser. The pin changes with the parser, in the pull request that adds it (not in this one), and the two repositories merge together as in [synapse.md](../integrations/synapse.md).

**Levels.**

| Level | What | Consent scope |
|---|---|---|
| L0 | The band as a standard HR strap | none |
| L1 | Feature v2.1 (HR, RR/RMSSD/SDNN once real, signal quality, motion level, motion artifact, battery, sensor health, stress class when present) and the Status record | `signal_quality`; `stress_monitoring` for the stress class; `hrv_analysis` for RR, RMSSD and SDNN |
| **L1R** | The raw research streams (ECG, PPG, IMU, power) shown as signals and stored for later | **`raw_signals` (new)** |
| L2 | `synapse_*` FIT developer fields on import | none beyond the import |
| L3 | Job/result plumbing to a worker | after launch, after the DPIA covers it |

**Rules for L1R.**

- **Its own scope, `raw_signals`**, separate and revocable ([ADR-0021](0021-consent-health-data.md)): server-authoritative, fail-closed on the device, re-read while the stream runs. Revoking stops the subscription at once, deletes the stored raw data and leaves a tombstone. Granting L1 consents never implies it.
- **The adapter subscribes to the research service only while an activity is recording or the "live signals" screen is open**, and unsubscribes when both end. The control point `stop` gates the raw streams on the band when the screen closes in the middle of a recording.
- **Storage is the frames as received** (`<id>.synapse-raw.bin`, each frame preceded by its length and the phone's monotonic reception time), append-only like the other sidecars ([ADR-0015](0015-local-activity-store.md), [ADR-0020](0020-sensor-raw-channels.md)). Averyn does not convert at write time: units, filtering and decimation are read-time functions of the contract, so a better filter never needs a re-recording. The server keeps the file verbatim and derives only decimated series ([ADR-0023](0023-activity-analysis-storage.md)).
- **Time.** The axis is the phone's monotonic clock at reception. The device timestamp (low 32 bits of microseconds) is extended to 64 bits per stream with the wrap flag and is used only to place samples *within* a frame and to align streams; it never overrides the phone's clock. The phone sends `set_time` on every connection so the band's wall clock (which it does not have by itself) is right when it matters.
- **Loss is data.** Per stream: a gap in `sequence`, the `dropped` count of each frame, a frame with an unknown version, stream or format, and the band's own loss counters from the Status record are all shown as markers on the signal with their reason, and counted in the activity's summary. A repeat or an older sequence is not a gap; a restart of the band is followed, not counted as thousands of lost frames.
- **Units come from the contract** (`display_unit`, `display_per_wire`): no unit is written by hand in a screen. A sentinel is a gap in the line, never `0`.
- **Wording.** The raw ECG is a signal from a wellness band, not a medical ECG ([ADR-0013](0013-health-data-special-category.md)): no diagnostic or arrhythmia wording anywhere, "via Synapse" on every chart, never mixed into Averyn's own charts. Every chart has a text or table equivalent ([TDD-0015](../design/TDD-0015-consumer-ux.md)).
- **Size.** The raw streams are about 2.4 kB/s, roughly 8.5 MB per hour. A cap per activity (proposed: 4 hours) and the sync policy (proposed: raw files upload on Wi-Fi only, after the activity) are part of the pull request that adds the endpoint; the figures are to be confirmed on a real band.

**Rules for L1 that change from ADR-0026.** Scaled 0..1 fields are read as `raw / 254`, not `/255` (255 is the sentinel); a stress class of `null` is not shown at all; `charging` is read only when the POWER bit of `sensor_health` is set; the contact flag of `0x2A37` follows the Bluetooth specification (2 = no contact, 3 = contact).

## Consequences

- Good: one viewer for everything the band measures; derived values and raw signals share one timeline, one loss model and one set of units; the device repository ships no UI.
- Good: the raw ECG has its own, explicit consent; a user who only wants a heart-rate strap is never asked for it.
- Cost: a new consent scope in the backend, a new sidecar with its tombstone, a larger export, and a signal view on Android, iOS and the web.
- Cost: the band can show nothing real until it has been run: every number above about timing, loss and size is a design figure until the hardware-in-the-loop run.
- Follow-ups (each its own pull request, paired with the device repository where marked): parser for contract 2.1 and raw frames with the re-pin ⇄; the L1 fixes (contact flag, `/254`, odd RR byte, MTU request); scopes, `devices` table and the sidecar endpoints; Android and iOS adapters and screens; the web history view.

## Revisit when

The first real band run shows the loss or the size figures wrong; or a second device wants raw display (then the rules become general); or a store review objects to a raw ECG view in a wellness app.
