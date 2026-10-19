# Metric: GPS quality

- **Status:** draft
- **Current algorithm version:** `quality-v2`

## Definition

Per-sample flags describing suspected GPS problems, and a per-activity summary derived from them. Flags never remove or modify raw samples (TDD-0001 F5) — they are metadata attached alongside the immutable `LocationSample`.

## Unit

Per-sample: a set of `QualityFlag` values. Per-activity: counts, a longest-gap duration (seconds), a sample-interval distribution, a percentage, and a `GOOD`/`FAIR`/`POOR`/`UNKNOWN` grade.

## Formula / method

`quality-v2`: same comparison rules as `quality-v1` (each sample is compared only to the last sample that became the baseline, in arrival order; a `DUPLICATE` or `NON_MONOTONIC_TIME` sample does not become the baseline, a sample flagged `SPEED_OUTLIER`, `JUMP`, `ALTITUDE_SPIKE` or `POOR_ACCURACY` does), with one difference: **a sample may have no reported accuracy at all**.

| Flag | Rule |
|---|---|
| `POOR_ACCURACY` | `horizontalAccuracyM > 30`, only when the sample has a reported accuracy |
| `DUPLICATE` | identical `latitude`, `longitude` and `timeMs` to the last accepted sample |
| `NON_MONOTONIC_TIME` | `elapsedRealtimeMs` does not strictly increase vs. the last accepted sample |
| `SPEED_OUTLIER` | implied speed (haversine leg / elapsed time) `> 50 m/s` and leg distance `< 500 m` |
| `JUMP` | implied speed `> 50 m/s` and leg distance `≥ 500 m` |
| `ALTITUDE_SPIKE` | `abs(altitudeM delta) / elapsed time > 10 m/s` (only when both samples have `altitudeM`) |

A sample can carry several flags. The first sample of an activity is only checked against the intrinsic `POOR_ACCURACY` threshold; the other five flags are comparisons against a previous sample and don't apply (nothing to compare against yet).

Per-activity report: count per flag; longest gap between consecutive *accepted* samples' `elapsedRealtimeMs`; interval distribution bucketed at `<2s, 2-5s, 5-15s, 15-60s, >60s`; percentage of the **measured** time where the active sample's `horizontalAccuracyM ≤ 20`; grade:

- `UNKNOWN`: no sample of the activity has a reported accuracy, so the accuracy criterion cannot be evaluated
- `GOOD`: flagged-sample ratio ≤ 5% **and** accuracy-≤20m time ≥ 90%
- `POOR`: flagged-sample ratio > 20% **or** accuracy-≤20m time < 50%
- `FAIR`: otherwise

`accuracyWithin20mPercent` is `null` when it cannot be computed (no sample had an accuracy). The other fields (flag counts, longest gap, interval distribution) are always reported.

## Required inputs

Ordered `LocationSample`s as received (including samples later flagged, since the assessor decides that itself).

## Minimum quality requirements

None — this metric *produces* the quality signal other metrics consume (see `docs/metrics/distance.md`'s exclusion list).

## Missing-data handling

`ALTITUDE_SPIKE` is skipped when either sample lacks `altitudeM`. `POOR_ACCURACY` is not raised when the sample has no reported accuracy: the criterion cannot be evaluated, and the sample is not "badly accurate", it is unmeasured. A signal gap (large `elapsedRealtimeMs` delta with otherwise-valid samples) is not itself a flag; it shows up in the longest-gap and interval-distribution fields.

## Version history

| Version | Date | Change |
|---|---|---|
| quality-v1 | 2026-09-27 | Initial: incremental threshold rules against the last accepted sample |
| quality-v2 | 2026-10-19 | Accuracy may be absent (`null`): no `POOR_ACCURACY` flag without a measurement, the accuracy percentage covers only the time with a measurement, and the grade is `UNKNOWN` when nothing was measured (imported GPX, TDD-0008) |

## Reliability level

Estimate. Threshold rules are a coarse proxy for actual signal quality; a genuinely fast downhill descent can trip `SPEED_OUTLIER`.

## Known limitations

No smoothing/Kalman model (deferred, TDD-0001 Q3); thresholds are not yet tuned against real-device data, only synthetic fixtures; `DUPLICATE` only catches exact repeats, not near-duplicates.

## Reference tests

`shared/tracking`'s `QualityAssessorTest` (one case per flag). `tests/gps-fixtures/` fixtures with non-empty `quality_flags` in `expected.json` (`outliers-and-duplicates`, `noisy-altitude`, `non-monotonic`), run by `FixtureConformanceTest`.
