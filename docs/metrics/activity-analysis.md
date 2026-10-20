# Metric: Activity analysis (the display series)

- **Status:** draft
- **Current algorithm version:** `series-v1`

## Definition

The **series** of an activity: the points the web app draws — the track, the altitude over the distance and the
heart rate over the time. It is a drawing, not a measurement: the numbers to trust are the splits
([splits.md](splits.md)), the elevation ([elevation.md](elevation.md)) and the totals of the activity.

The series is bounded: at most **2 000 points** of the track and **1 000 points** of the heart rate, whatever the
length of the activity.

## Unit

Meters of distance, milliseconds of time, meters of altitude, beats per minute.

## Formula / method

`series-v1`:

- The points of the track are the accepted samples, with the cumulative distance of
  [distance.md](distance.md) and the time from the first sample of the activity.
- The points of the heart rate are the usable `HR` records, with the time from the first sample.
- When there are more points than the bound, the **largest triangle three buckets** decimation keeps the points
  that carry the shape of the curve and drops the ones that only repeat it. The first and the last point are
  always kept.
- The heart rate is decimated **stretch by stretch**: a stretch is a run of samples that are less than two seconds
  apart. The sensor was silent between two stretches, and the decimation never mixes them. Every stretch keeps
  its first and its last point (or the one point the budget leaves it), and what is left of the bound goes to the
  stretches that have more samples.
- Every point carries the **segment** it belongs to: the recording interval of the track (a change of segment is a
  pause) and the stretch of the heart rate (a change of segment is a dropout). The web app breaks the line there:
  a line across a pause or across a dropout would invent what was not measured.

## Required inputs

The same inputs as the splits and the elevation.

## Missing-data handling

An altitude that was not measured is a null in the series, and the chart shows the hole.

## Version history

| Version | Date | Change |
|---|---|---|
| series-v1 | 2026-10-20 | Initial: 2 000 track points, 1 000 heart-rate points, LTTB, segments |

## Reliability level

**Display.** The series is lossy on purpose: it is what a chart can show.

## Known limitations

- The series is not the source of the numbers: a split boundary can fall between two points of the series.
- The heart rate of a stretch is decimated, so the peaks of a short stretch may be missing from the chart. The
  average, the maximum and the minimum of the activity are not decimated and are shown as they are.

## Reference tests

`shared/analysis`'s `ActivityAnalyzerTest` (the series is bounded and keeps the first and the last point) and the
web app's `Analysis.test.ts` (the line breaks at a pause and at a dropout).
