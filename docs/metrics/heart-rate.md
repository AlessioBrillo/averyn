# Metric: Heart rate

- **Status:** draft
- **Current algorithm version:** `hr-v1`

## Definition

Heart rate (beats per minute) measured during an activity, over samples recorded while the activity is in `RECORDING` state. Samples recorded while `PAUSED` are stored but excluded from the metric (auto-pause is a classification of time, not discarded data — TDD-0001 §5.6).

## Unit

Beats per minute (bpm), integer platform values.

## Formula / method

`hr-v1`: over every `HR` record whose enclosing state interval is `RECORDING` (same ordering rule as `distance-v1`, docs/metrics/distance.md):

- `avg_hr_bpm = mean(bpm)` over qualifying samples; `0` when none
- `max_hr_bpm = max(bpm)`; `0` when none
- `min_hr_bpm = min(bpm)`; `0` when none
- `hr_coverage_percent = 100 × qualifying samples / expected samples` (expected = activity duration in seconds at the configured 1 s sampling interval; capped at 100)
- `hr_dropouts = expected samples − qualifying samples` (floored at 0)

No interpolation across gaps; no smoothing; zones/time-in-zone require a user profile (deferred increment).

## Required inputs

`HR` records (`bpm`, optional `contact`) with their `elapsedRealtimeMs`, plus pause/resume state boundaries from the activity's `EVENT` records.

## Minimum quality requirements

The caller passes only records stored by the adapter; platform-reported `bpm <= 0` or `> 240` is counted as an artifact (see `rr-quality-v1` for RR-side artifacts), not silently dropped: `hr_v1_artifacts` counts them alongside the metric.

## Missing-data handling

A signal gap becomes missing samples in coverage/dropouts, exactly like a GPS gap in `distance-v1` (gap policy planned for a later version). `hr_source` records which sensor produced the values (`ble-hr`, `health-connect`, …).

## Version history

| Version | Date | Change |
|---|---|---|
| hr-v1 | 2026-10-18 | Initial: avg/max/min, coverage, dropouts, no interpolation |

## Reliability level

Derived-reliable. Optical straps under-report during high-cadence motion; chest straps are the reference.

## Known limitations

No motion-artifact filtering in v1; chest-strap vs optical not distinguished; zones require max HR (user profile) which does not exist yet.

## Reference tests

`shared/metrics` unit tests with synthetic HR fixtures; `shared/tracking`'s `FixtureConformanceTest` does not cover HR (no GPX fixture for HR — HR is never in GPX output).
