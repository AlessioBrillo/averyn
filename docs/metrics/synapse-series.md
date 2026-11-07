# Metric: Synapse series (what Averyn derives from a band's sidecars)

- **Status:** draft (implemented with tests on the device repository's synthetic sessions; nothing has run on a band)
- **Current algorithm version:** `synapse-series-v1`

This is the transformation Averyn applies to what a Synapse band sent ([ADR-0030](../adr/0030-synapse-contract-v2-1-and-raw-signals.md)); it is **not** a metric about the athlete. Every number it shows was computed by the band, is stored as imported data with `source: "synapse-device"`, and is shown as "via Synapse" ([ADR-0014](../adr/0014-sensor-integration-contract.md)). The version below is the version of the *transformation* (units, gap detection, decimation), not of the band's firmware.

## Definition

For one activity, from its two sidecars (`<id>.synapse.bin`, the consumer records; `<id>.synapse-raw.bin`, the raw frames — both exactly as received, `u16 length | i64 received_ns | frame`):

- **Derived values** (`signal_quality`): parallel arrays at the phone's reception time, one entry per feature frame: heart rate, PPG quality, motion artifact, motion level, battery; and, only with their own consent, the stress class (`stress_monitoring`), the last RR interval (`rr_recording`) and the band's RMSSD and SDNN (`hrv_analysis`).
- **Raw signals** (`raw_signals`): per stream (ECG, PPG, accelerometer, gyroscope, magnetometer, power) a set of **segments** and, for any window, the minimum and maximum of every bucket.
- **Gaps**: every stretch with no data, with its reason.

## Unit

Taken from the contract's stream table, never written by hand in a screen: ECG in µV, PPG in counts, accelerometer in mg, gyroscope in °/s (it travels in mrad/s: wire value × 0.0573), magnetometer in µT (wire × 0.01), power in mV, mA, mW and percent. Fractions (quality, artifact, motion) are 0.0 to 1.0, from `raw / 254` (255 is the "not available" sentinel). The RR interval is in milliseconds (the wire carries 1/1024 s). Times are milliseconds since the recording began, on the **phone's** clock.

## Formula / method

1. **Time.** The axis is the phone's monotonic clock at reception. The first sample of a segment is placed at the reception time of its first frame minus the frame's own duration (`values / channels / rate`); later samples follow at the stream's rate. The band's own clock (a 32-bit **microsecond** counter in raw frames, a 32-bit **millisecond** counter in the feature frame) is extended past its wrap with the wrap flag and only places samples *within* a frame and aligns streams; it never overrides the phone's.
2. **Segments and gaps.** A segment ends, and a gap is recorded, when frames were lost (a hole in the 16-bit sequence), when the frame's `dropped` byte says the band discarded samples, or when the band restarted (the sequence fell by more than 64 frames). A repeated or late frame (a step back of up to 64) adds nothing and does not move the counter. A frame of an unknown version, stream or format, or one that is cut, is counted by reason and never interpreted.
3. **Pyramid.** Level 0 is the signal; level *k* summarises 10^k samples per bucket as the minimum and the maximum per channel. A window request returns, for each overlapping segment, the finest level whose bucket count does not exceed the requested budget (`maxPoints`, at most 4000), so a 500 Hz ECG is shown whole and can be zoomed to a single beat. A bucket keeps its extremes: an average would lose the R wave.
4. **Not available** is `null` all the way to the screen. A null breaks the line; it is never drawn as zero.

## Required inputs

A feature sidecar and/or a raw sidecar, and the owner's current consents. The server checks the consent again when it reads (fail closed): no consent now, no data, whatever is stored.

## Minimum quality requirements

None imposed: a stretch with poor signal quality is shown, with the band's own quality index next to it. Averyn does not filter, score or interpret the signals.

## Missing-data handling

Described above: a gap is a break in the line and a row in a table (start, length, reason, count). A missing field is `null`. A sidecar cut by a killed app is read up to its last whole record.

## Version history

| Version | Date | Change |
|---|---|---|
| synapse-series-v1 | 2026-11-07 | Initial: phone-clock axis, segments and gaps from the contract's sequence rule, min/max pyramid by factor 10, units from the contract |

## Reliability level

estimate — the sample positions on the time axis. The values are the band's, and Averyn cannot reproduce them.

## Known limitations

- **No band has produced a frame yet.** The rules are proven on the device repository's synthetic sessions and answer key, not on a radio.
- The anchor of a segment includes the BLE latency (tens of milliseconds), and the drift between the phone's clock and the band's is not modelled. Measure both before trusting a time axis finer than that, or before aligning two streams to a few milliseconds.
- The pyramid is built when a recording is first read and kept in a small in-memory cache, not at upload and not persisted: a restart of the server rebuilds it. Persist it next to the sidecar when reads get slow.
- Four hours of the raw streams is about 35 MB; the server caps a raw sidecar at 48 MB.
- The raw ECG of a wellness band is not a medical ECG: no diagnostic wording anywhere.

## Reference tests

`shared/sensors`: `SynapseVectorsTest`, `SynapseFixturesTest` (the seven raw scenarios, the L1 scenario and the 60 s sidecars against the device repository's answer key), `SynapseSidecarTest`. `backend`: `SynapseRecordingTest` (gaps, segments, the R wave surviving decimation, units), `SynapseFeaturesTest` (consent gating, masking), `SynapseRoutesTest` (consent, revocation, tombstone, quota, isolation, cascade). `apps/web`: `synapseSeries.test.ts`.
