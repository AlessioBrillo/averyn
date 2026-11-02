# Metric: Heart rate

- **Status:** draft (implemented; `stable` after the device matrix row S15 is green)
- **Current algorithm version:** `hr-v2`

## Definition

Heart rate (beats per minute) measured during an activity, over samples recorded while the activity is in `RECORDING` state. Samples recorded while `PAUSED` are stored but excluded from the metric (auto-pause is a classification of time, not discarded data — TDD-0001 §5.6).

## Unit

Beats per minute (bpm), integer platform values.

## Formula / method

`hr-v2`: over every `HR` record whose enclosing state interval is `RECORDING` (same ordering rule as `distance-v1`, docs/metrics/distance.md):

- `avg_hr_bpm = mean(bpm)` over qualifying samples; `0` when none
- `max_hr_bpm = max(bpm)`; `0` when none
- `min_hr_bpm = min(bpm)`; `0` when none
- `hr_coverage_percent = 100 × qualifying samples / expected samples` (expected = the time the activity was **recording**, the pauses excluded, at the configured 1 s sampling interval; capped at 100)
- `hr_dropouts = expected samples − qualifying samples` (floored at 0)

No interpolation across gaps; no smoothing. The time in each zone is a separate metric, [`hr-zones-v1`](hr-zones.md).

`hr-v1` counted the **elapsed** time of the activity in the denominator, pauses included, while the samples of a
paused activity are not recorded: a strap that worked perfectly through a half-hour pause was reported as half
covered. `hr-v2` counts the recording time, so the coverage says what it means. The average, the maximum and the
minimum do not change between the two versions.

## Required inputs

`HR` records (`bpm`, optional `contact`) with their `elapsedRealtimeMs`, plus pause/resume state boundaries from the activity's `EVENT` records.

## Minimum quality requirements

The frame parser rejects a `bpm` outside 0..300 and an RR outside 1..3000 ms and reports each as `ParseResult.Invalid`: the rest of the frame is kept and the value is never thrown or silently lost. The caller passes only records stored by the adapter; a `bpm` of 0 is stored ("no reading") and a value above 240 that the parser accepted is counted by the metric as an artifact (`artifacts` in the analysis), not dropped. See `rr-quality-v1` for RR-side artifacts.

The RR unit on the wire is 1/1024 s; the parser stores milliseconds (`(raw * 1000 + 512) / 1024`). The RR sidecar's own `formatVersion` is 2 for milliseconds; a sidecar without the field (written before 2026-10-30) is 1, holds the raw unit as if it were milliseconds, and is read as it was written, never converted.

## Missing-data handling

A signal gap becomes missing samples in coverage/dropouts, exactly like a GPS gap in `distance-v1` (gap policy planned for a later version). `hr_source` records which sensor produced the values (`ble-hr`, `health-connect`, …).

## Version history

| Version | Date | Change |
|---|---|---|
| hr-v1 | 2026-10-18 | Initial: avg/max/min, coverage, dropouts, no interpolation |
| hr-v2 | 2026-10-20 | The coverage is over the recording time, the pauses excluded. The average, the maximum and the minimum do not change. |

## Reliability level

Derived-reliable. Optical straps under-report during high-cadence motion; chest straps are the reference.

## Known limitations

No motion-artifact filtering in v1; chest-strap vs optical not distinguished; the zones need the maximum heart rate of the profile ([hr-zones.md](hr-zones.md)).

## Reference tests

`shared/sensors`'s unit tests with synthetic HR fixtures; `shared/analysis`'s `ActivityAnalyzerTest` (the coverage over the recording time, the pauses excluded); `shared/tracking`'s `FixtureConformanceTest` does not cover HR (no GPX fixture for HR — HR is never in GPX output).
