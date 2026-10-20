# Metric: Automatic splits

- **Status:** draft
- **Current algorithm version:** `splits-v1`

## Definition

The automatic splits of an activity: the track cut into pieces of a fixed length, one every kilometer for the
foot sports and every five kilometers for a ride. Each split holds the distance it covers, the time it took, its
average speed, its climb and its heart rate.

A split is not a lap: it is cut by the distance, not by the device or by the user.

## Unit

Metres for the distance, milliseconds for the time, meters per second for the speed, meters for the climb, beats
per minute for the heart rate.

## Formula / method

`splits-v1`:

- The length of a split is **1 000 m** for `RUN`, `WALK` and `HIKE`, and **5 000 m** for `RIDE`. A kilometer every
  hundred meters is unreadable on a long ride, and five kilometers is what a cyclist expects.
- The splits are cut on the **cumulative distance** of the accepted samples (the same ones `distance-v1` sums, see
  [distance.md](distance.md)).
- The boundary of a split falls **inside the leg that crosses it**: the time of the boundary is interpolated
  linearly between the two samples of that leg, so the splits add up to the whole track and the time of the split
  is the real time, not the time of a sample.
- The **moving time** of a split is the sum of the legs of the split whose speed is at least 0.5 m/s (the same
  threshold as `time-v1`, see [moving-time.md](moving-time.md)). The **elapsed time** of a split is the wall time
  between its first and its last point, a pause inside it included.
- A **pause is never crossed by distance**: the samples of a recording interval start where the previous one
  ended, so a split that spans a pause holds the distance of both intervals and the time of the pause in its
  elapsed time.
- The last split is **partial** when the activity ended before the next boundary. It is a split like the others,
  marked as partial, and it is not a best effort.
- The climb and the descent of a split are the elevation changes that fall inside it (see
  [elevation.md](elevation.md)). The heart rate of a split is the mean of the usable samples that fall inside its
  time range, and it is null when there is none.

The sum of the distances of the splits is the distance of the activity, up to the rounding of the interpolation.

## Required inputs

The accepted `LocationSample`s of every continuous recording interval, the `StateEvent` log (for the pauses) and
the `HR` records (for the heart rate of the splits).

## Minimum quality requirements

Same exclusion list as [distance.md](distance.md): a `DUPLICATE`/`NON_MONOTONIC_TIME`/`JUMP`-flagged sample does
not start or end a leg.

## Missing-data handling

A signal gap is one leg like any other: a split can be crossed by it, and the time of the boundary is interpolated
across it. `distance-v1` has no gap policy yet, so a split of an activity with a long gap is as wrong as the
distance of that activity. The splits inherit the limitation of the metric they are built on; they do not hide it.

An activity with fewer than two accepted samples has no split at all.

## Version history

| Version | Date | Change |
|---|---|---|
| splits-v1 | 2026-10-20 | Initial: 1 km (5 km for a ride), interpolated boundaries, partial last split, moving time |

## Reliability level

Derived-reliable, as far as the distance it is built on is.

## Known limitations

- The distance of a split inherits the jitter of the GPS: a split of a standing start is a few meters off.
- The moving time of a split excludes the stationary time, so a split with a long stop is not comparable with one
  without: this is the same rule as the average speed of the activity, and it is deliberate.
- A pause inside a split makes its elapsed time longer than its moving time: the two are both shown.

## Reference tests

`shared/analysis`'s `ActivityAnalyzerTest` (a straight run, a ride, a partial last split, a pause, the stationary
legs) and the backend's `ActivityAnalysisTest` (the splits of a real uploaded activity add up to its distance).
