# Metric: Heart-rate zones

- **Status:** draft (implemented; `stable` after the device matrix row S33 is green)
- **Current algorithm version:** `hr-zones-v1`

## Definition

The time an activity spent in each of five heart-rate zones, where a zone is a band of the user's **maximum heart rate** (the setting of the [profile](../design/TDD-0012-profile-zones-phone-map.md)). The zones are a view of the heart rate of the activity, not a second measurement: they are made of the time at each bpm that the analysis stores ([activity analysis](activity-analysis.md), `series-v2`), and they are computed when the analysis is read, never stored, so changing the maximum heart rate changes every past activity at once and needs no recomputation.

## Unit

Milliseconds per zone; the limits of a zone in beats per minute.

## Formula / method

`hr-zones-v1`, for a maximum heart rate `M` (an integer, bpm):

| Zone | From | To |
|---|---|---|
| Z1 | 0 | below 60 % of `M` |
| Z2 | 60 % of `M` | below 70 % |
| Z3 | 70 % of `M` | below 80 % |
| Z4 | 80 % of `M` | below 90 % |
| Z5 | 90 % of `M` | no upper limit |

- The first bpm of a zone is `ceil(M × percent / 100)`, so every bpm belongs to exactly one zone and the lower limit of a zone is in that zone. With `M = 190` the zones begin at 0, 114, 133, 152 and 171 bpm.
- `time(zone)` is the sum of the time at the bpm that fall in the zone. The five times add up to the time with a heart rate.
- The time at each bpm is `timeAtBpm`, from the heart-rate samples recorded while recording (`hr-v2`): a sample holds its bpm until the next sample of its stretch, the last sample of a stretch holds it for 1 s (the configured sampling interval), and a silence of the sensor of more than 2 s ends the stretch and is in no bpm.

## Required inputs

`timeAtBpm` of the analysis of the activity, and the maximum heart rate of the profile (100 to 230 bpm).

## Minimum quality requirements

Samples with a bpm outside 1 to 240 are not in the histogram (the same rule as the heart-rate series). Without a heart rate there is no histogram and no zones.

## Missing-data handling

No maximum heart rate in the profile: there are no zones, and the clients say why. A dropout of the sensor is time in no zone, so the zones can add up to less than the recording time; the heart-rate coverage of the activity says by how much.

## Version history

| Version | Date | Change |
|---|---|---|
| hr-zones-v1 | 2026-10-25 | Initial: five zones at 60 / 70 / 80 / 90 % of the maximum heart rate |

## Reliability level

Derived-reliable. As good as the maximum heart rate the user typed; an estimate of it from the age is not offered.

## Known limitations

Percent-of-maximum zones, not heart-rate-reserve zones (those need a resting heart rate). The zone limits are not editable. Optical straps under-report during high-cadence motion ([heart rate](heart-rate.md)).

## Reference tests

`shared/analysis`'s `HeartRateZonesTest` (the limits and their rounding, every bpm in one zone, the zones add up to the histogram, a changed maximum) and `ActivityAnalyzerTest` (the time at each bpm); `backend`'s `ActivityAnalysisTest` (the zones follow the profile without recomputing the analysis, an activity without a heart rate has none, another user's maximum is not used).
