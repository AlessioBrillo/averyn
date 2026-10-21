# Metric: Best efforts

- **Status:** draft
- **Current algorithm version:** `best-efforts-v1`

## Definition

The **best effort** of an activity for a distance: the fastest stretch of the track that covers exactly that
distance. It is not a split: a split is a fixed piece of the track, a best effort is the fastest kilometre (or
five, or ten) of the whole activity, wherever it happens to be.

The **personal record** of an account is the best effort of the account for a distance: the fastest of all the
activities. It is a projection of the best efforts, not a measurement of its own.

## Unit

Metres for the distance, milliseconds for the time, meters per second for the speed, seconds per kilometer for
the pace.

## Formula / method

`best-efforts-v1`:

- The distances an effort is measured over are the standard ones of the sport: **1 km, 5 km, 10 km, half
  marathon (21 097,5 m) and marathon (42 195 m)** for `RUN`; **5 km, 10 km, 20 km, 40 km, 50 km and 100 km**
  for `RIDE`; **1 km, 5 km and 10 km** for `WALK` and `HIKE`. A change of this list is a change of the
  algorithm.
- The effort is searched in the **accepted samples** of the activity, the same ones `distance-v1` sums (see
  [distance.md](distance.md)): the same track, the same distance, the same time as the splits and the totals.
- A **pause is never crossed**: the samples of a recording interval start where the previous one ended, and an
  effort lives inside one interval.
- A **signal gap is never crossed** either: a silence longer than ten seconds (or longer than five times the
  usual interval between two samples, when the file samples less often than once a second) breaks the track
  into stretches, and an effort lives inside one of them. The distance covered during a long silence is
  uncertain, and counting it would give the athlete distance nobody ran.
- The window is found twice, and the faster of the two is the effort:
  - the window **starts on a sample** and ends inside the leg that crosses the target distance;
  - the window **ends on a sample** and starts inside the leg that crosses the target distance.

  One of the two is enough when the speed does not change inside the window. In general it is not: the time to
  cover a distance is the shortest when the window is as fast as possible, and the window that does that can
  start in the middle of a leg.
- The **time of the effort** is the **moving time** of the window: the legs whose speed is at least 0.5 m/s
  (the same threshold as `time-v1`, see [moving-time.md](moving-time.md)). The wall time of the window is
  stored next to it, and the two are both shown.
- A window that does not move, or whose average speed is **above what the sport can do** (12 m/s for `RUN`,
  35 m/s for `RIDE`, 6 m/s for `WALK`, 5 m/s for `HIKE`), is not an effort: the fastest plausible window is
  used instead. The caps are generous on purpose: they catch what no athlete can do, not what an athlete did
  badly.

## Required inputs

The accepted `LocationSample`s of every continuous recording interval and the sport of the activity.

## Minimum quality requirements

- At least two accepted samples, and a track of at least the target distance.
- The effort never crosses a pause and never crosses a signal gap.
- The window moves, and its average speed is within the cap of the sport.
- The activity must have finished (`COMPLETED`) to hold a **personal record**; the best efforts of an
  interrupted activity are still shown on its page.
- A **personal record** is only held by an activity whose quality is not `POOR`: the platform itself says the
  distance of such an activity is not reliable. An imported file (`UNKNOWN`) is eligible: `UNKNOWN` is not a
  bad grade, it is an unmeasured one.

## Missing-data handling

- An activity shorter than the smallest target of its sport has no best effort.
- An activity with no altitude, no heart rate or no accuracy has its best efforts all the same: they are built
  on the track and the time, and nothing else.
- A signal gap shorter than the threshold is crossed: the distance and the time of the gap are interpolated
  like any other leg, and the effort can be a little off.

## Version history

| Version | Date | Change |
|---|---|---|
| best-efforts-v1 | 2026-10-21 | Initial: the standard distances of the sport, a sliding window that never crosses a pause or a gap, the moving time, a plausibility cap |

## Reliability level

Derived-reliable, as far as the distance and the time it is built on are. It inherits the limits of
`distance-v1`: the jitter of the GPS inflates the distance at low speed, and there is no gap policy in that
metric yet.

## Known limitations

- The distance of an effort is the distance of `distance-v1`: a stretch of poor GPS inflates it, and the
  effort looks faster than it was. The quality filter (`quality-v2`) is the first line of defence: a sample
  flagged `JUMP` never counts towards the distance. The plausibility cap is the second one, and it only
  catches the windows that are mostly wrong.
- A silence shorter than the threshold is interpolated: the athlete may have gone around a corner, and the
  straight line is shorter than the real path. The effort is then a little slower than the truth, never
  faster.
- The moving time of the effort does not count the stops: a window with a stop in the middle is as fast as
  one without. It is the same rule as the pace of a split, and it is deliberate.
- The distances are the metric ones: the imperial ones come with the units of the account, in a later
  increment.

## Reference tests

`shared/analysis`'s `BestEffortsTest` (the targets of the sport, a straight run, the fastest window that is
not the first split, a window that starts in the middle of a leg, a pause, a signal gap, the stationary legs,
an implausible window) and the backend's `PersonalRecordsTest` (the efforts are stored with the analysis, the
record is the fastest of the account, the eligibility, the deletion of the activity that holds a record).
