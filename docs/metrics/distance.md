# Metric: Distance

- **Status:** draft
- **Current algorithm version:** `distance-v1`

## Definition

Length of the recorded path over ground, summed over consecutive accepted samples while the activity is in a moving (non-paused) state.

## Unit

Meters.

## Formula / method

`distance-v1`: sum of great-circle (haversine) distances between consecutive samples, using mean Earth radius R = 6 371 008.8 m (IUGG). No smoothing. Elevation is ignored (2D distance).

`d = 2R · asin(√(sin²(Δφ/2) + cos φ₁ · cos φ₂ · sin²(Δλ/2)))`

## Required inputs

Ordered `LocationSample`s (latitude, longitude); pause intervals.

## Minimum quality requirements

The function sums whatever list it is given: **the caller passes only accepted samples** (excluding those flagged `DUPLICATE`, `NON_MONOTONIC_TIME`, `JUMP`). `POOR_ACCURACY` samples are included in v1 (a known limitation, addressed in v2).

## Missing-data handling

v1 has no gap policy: a signal gap becomes one straight segment between the last sample before and the first after it. A gap policy (exclude segments over a maximum gap, report in the quality report) is planned for v2 — tracked in the MVP-0 issues.

## Version history

| Version | Date | Change |
|---|---|---|
| distance-v1 | 2026-09-26 | Initial: haversine sum, no smoothing |

## Reliability level

Derived-reliable. GPS jitter inflates distance for slow movement (typically a few %).

## Known limitations

Ignores altitude (3D distance); jitter at low speed inflates results; not comparable to wheel-sensor distance.

## Reference tests

`shared/metrics` unit tests (hand-computed haversine values). `tests/gps-fixtures/straight-line-1km` (expects 1000 m ± 1 m) will be wired in once the GPX importer exists (MVP-0).
