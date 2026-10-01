# Metric: Speed and pace

- **Status:** draft
- **Current algorithm version:** `speed-v1`

## Definition

Current speed (the most recent leg's speed), average speed (distance over moving time) and pace (average speed re-expressed as seconds per kilometer, for run/walk/hike display).

## Unit

Speed: meters/second. Pace: seconds/kilometer.

## Formula / method

`speed-v1`:

- **Current speed** = the last accepted leg's haversine distance (`docs/metrics/distance.md`) divided by its elapsed time (monotonic clock). Not the platform-reported `speedMps` (TDD-0001 §5.3: "not trusted blindly") — derived the same way as every other distance-based metric, for consistency.
- **Average speed** = total `distance-v1` distance ÷ `time-v1` moving time. `0` while moving time is `0`.
- **Pace** = `1000 / averageSpeedMps` seconds/km. Undefined (not computed) when average speed is `0`, to avoid a division-by-zero "infinite pace" display.

## Required inputs

`distance-v1` distance, `time-v1` moving time, and the last accepted leg for current speed.

## Minimum quality requirements

Same accepted-sample definition as `distance-v1`.

## Missing-data handling

A signal gap's leg is excluded the same way `distance-v1` excludes it (i.e. not excluded — see that spec's known limitation): a long gap can transiently spike current speed for one leg. Average speed is more robust since it's a ratio over the whole activity.

## Version history

| Version | Date | Change |
|---|---|---|
| speed-v1 | 2026-09-27 | Initial: derived (not platform-reported) speed, average over moving time |

## Reliability level

Derived-reliable for average speed/pace; estimate for current speed (single-leg, sensitive to jitter).

## Known limitations

Current speed on a single short leg is noisy at low speed; no smoothing window (planned alongside `distance-v2`, TDD-0001 Q3 follow-up).

## Reference tests

`shared/metrics`'s `ActivityMetricsTest` (constant-speed case, pace-undefined-at-zero-distance case). `tests/gps-fixtures/straight-line-1km` is consistent with 10 m/s / 100 s/km, though pace isn't yet a field `FixtureConformanceTest` asserts — only `distance_m`/`duration_s`/`moving_time_s`/`quality_flags` are (see the fixtures README).
