# Metric: Moving, paused and stationary time

- **Status:** draft
- **Current algorithm version:** `time-v1`

## Definition

Splits an activity's elapsed wall time into three durations: **paused** (the user explicitly paused), **stationary** (recording, but implied speed is below the moving threshold — "soft" auto-pause classification, TDD-0001 §5.6) and **moving** (the remainder). `elapsed = paused + stationary + moving`.

## Unit

Seconds.

## Formula / method

`time-v1`: elapsed time is `lastSampleElapsedRealtimeMs - firstSampleElapsedRealtimeMs`, using the monotonic clock (never wall-clock `timeMs`, which can jump). Paused time is the sum of intervals between a `PAUSED` `StateEvent` and the following `RECORDING` (resume) event. Within non-paused time, a leg between two consecutive accepted samples counts as **stationary** if its implied speed is `< 0.5 m/s`, otherwise **moving**. This is a per-leg classification applied at metric time (TDD-0001 §5.6) — the samples themselves are never dropped or altered by it.

## Required inputs

Ordered accepted `LocationSample`s, and the activity's `StateEvent` log (for pause/resume intervals).

## Minimum quality requirements

Same exclusion list as `docs/metrics/distance.md` (a `DUPLICATE`/`NON_MONOTONIC_TIME`/`JUMP`-flagged sample doesn't start or end a leg).

## Missing-data handling

A signal gap (large time delta between two accepted samples) is counted as **stationary**, not moving — a conservative choice: v1 doesn't try to guess what happened during the gap. Revisited if fixtures show this misclassifies clear "recording resumed after a tunnel" cases.

## Version history

| Version | Date | Change |
|---|---|---|
| time-v1 | 2026-09-27 | Initial: 0.5 m/s stationary threshold, gaps counted as stationary |

## Reliability level

Derived-reliable.

## Known limitations

Fixed 0.5 m/s threshold isn't sport-aware (a slow hike leg can read as stationary); no hysteresis, so jitter near the threshold can flap leg-to-leg — acceptable at MVP-0 since it only affects the moving/stationary split, not distance.

## Reference tests

`shared/metrics`'s `ActivityMetricsTest` (a pause fixture, a stationary-leg fixture). `tests/gps-fixtures/signal-gap` (run by `FixtureConformanceTest`) exercises the gap-counted-as-stationary rule end to end. A full manual pause/resume fixture needs a multi-segment GPX loader — tracked in the fixtures README as a follow-up.
