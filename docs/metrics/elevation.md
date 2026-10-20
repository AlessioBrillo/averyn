# Metric: Elevation

- **Status:** draft
- **Current algorithm version:** `elevation-v1`

## Definition

The altitude of the activity and the climb and the descent along it: the gain, the loss, the lowest, the highest
and the mean altitude.

The altitude is the one the samples carry: the altitude of the platform (GNSS) or the `<ele>` of an imported GPX
file. It is **not** a digital elevation model: Averyn does not know the shape of the ground, it knows what the
receiver reported.

## Unit

Meters.

## Formula / method

`elevation-v1`:

- The altitude of a point is the **median of the five points around it** (the window shrinks at the ends of a
  recording interval). One wrong altitude does not move the profile.
- The **climb** and the **descent** are counted with a **3 m threshold**: the altitude only counts when it moved
  3 m more than the last altitude that counted. The noise of the altitude is not a mountain, and a threshold
  below the noise of the receiver would turn a flat path into a climb.
- The reference altitude is **reset at every pause**: the altitude before a pause and the altitude after it are
  not compared.
- The **minimum**, the **maximum** and the **mean** altitude are those of the smoothed series.
- The **coverage** is the share of the accepted samples that carried an altitude. An activity with no altitude at
  all has **no elevation**: the numbers are null, not zero. A flat activity and an activity whose altitude was
  never measured are not the same thing.

## Required inputs

The accepted `LocationSample`s of every continuous recording interval, with their optional `altitudeM`.

## Minimum quality requirements

The same accepted samples as [distance.md](distance.md). The samples flagged `ALTITUDE_SPIKE` (a vertical speed
above 10 m/s) are not dropped: the median of five already removes an isolated spike, and a longer one is a
receiver that was wrong for a while, not a climb.

## Missing-data handling

A sample without an altitude is a hole in the profile, not a zero: the chart shows the gap and the coverage says
how much of the activity was measured. The climb and the descent skip the holes.

## Version history

| Version | Date | Change |
|---|---|---|
| elevation-v1 | 2026-10-20 | Initial: median of five, 3 m threshold, reset at the pauses, coverage |

## Reliability level

**Estimate.** The vertical accuracy of a GNSS receiver is much worse than its horizontal one, and the altitude of
an imported file is whatever the device that wrote it reported. The numbers are a profile of what was reported,
not a survey.

## Known limitations

- No digital elevation model: a phone in a valley and a phone on a hill report different altitudes for the same
  place.
- The 3 m threshold leaves the last 3 m of a climb uncounted, and it undercounts the climbs of a few metres.
- The threshold was chosen from the fixtures and from the noisier receivers; it is a named constant, and a
  different one is a new version.

## Reference tests

`shared/analysis`'s `ActivityAnalyzerTest` (a steady climb, an altitude that only jitters, an activity without
altitude) and the backend's `ActivityAnalysisTest`.
