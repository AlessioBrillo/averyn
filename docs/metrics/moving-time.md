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

A signal gap (large time delta between two accepted samples) is one leg like any other: it is classified by its implied speed (distance between the two samples over the gap). A long gap over a short distance therefore comes out stationary, which is the conservative case v1 was designed for; a gap that spans a real displacement at `≥ 0.5 m/s` counts as moving. v1 does not try to guess what happened during the gap. Revisited if fixtures show this misclassifies clear "recording resumed after a tunnel" cases.

The same rule covers the gap of an interrupted and recovered activity (kill, reboot): the elapsed time is real time, the leg across it is classified like any other, and a negative or reset clock never reaches this formula because the monotonic clock is made continuous at recovery ([TDD-0006 §5.2](../design/TDD-0006-field-readiness.md)).

## Version history

| Version | Date | Change |
|---|---|---|
| time-v1 | 2026-09-27 | Initial: 0.5 m/s stationary threshold, gaps counted as stationary |
| time-v1 | 2026-10-15 | Clarification, no behavior change: the text said gaps are always stationary; the implementation and the formula above classify a gap by its implied speed. Recovery across reboot and replay equivalence fixed in code ([TDD-0006](../design/TDD-0006-field-readiness.md)); no data existed outside development, so no new version |

## Reliability level

Derived-reliable.

## Known limitations

Fixed 0.5 m/s threshold isn't sport-aware (a slow hike leg can read as stationary); no hysteresis, so jitter near the threshold can flap leg-to-leg — acceptable at MVP-0 since it only affects the moving/stationary split, not distance.

## Reference tests

`shared/metrics`'s `ActivityMetricsTest` (a pause fixture, a stationary-leg fixture). `tests/gps-fixtures/signal-gap` (run by `FixtureConformanceTest`) exercises the gap-counted-as-stationary rule end to end. A full manual pause/resume fixture needs a multi-segment GPX loader — tracked in the fixtures README as a follow-up.
