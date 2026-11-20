# Metric specs

Every metric has one spec with its definition, unit, method, required inputs,
quality rules, version history and reference tests. The template is
[template.md](template.md); the lifecycle is:

- **`draft`** — the spec exists, the code does not (or is not complete).
- **`implemented`** — the code and its tests are merged, but the device matrix
  row that covers the metric is not green. This is not a claim that the numbers
  are right on a real device.
- **`stable`** — the device matrix row is green on the required devices, with a
  named build. Only then may a client rely on the numbers.
- **`deprecated`** — superseded by a new version; the old version is never
  edited.

A behavior change is a new `algorithm_version` and a new section in the spec.
Existing versions are immutable.

| Metric | Status | Version | Evidence |
|---|---|---|---|
| [Activity analysis (series)](activity-analysis.md) | implemented | `series-v2` | pending |
| [Best efforts](best-efforts.md) | implemented | `best-efforts-v1` | pending |
| [Distance](distance.md) | implemented | `distance-v1` | pending |
| [Elevation](elevation.md) | implemented | `elevation-v1` | pending |
| [GPS quality](gps-quality.md) | implemented | `quality-v2` | pending |
| [Heart rate](heart-rate.md) | implemented | `hr-v2` | pending row S15 |
| [Heart-rate zones](hr-zones.md) | implemented | `hr-zones-v1` | pending row S33 |
| [Moving, paused and stationary time](moving-time.md) | implemented | `time-v1` | pending |
| [RR interval quality](rr-quality.md) | draft (not implemented) | `rr-quality-v1` | not in launch scope |
| [Speed and pace](speed-and-pace.md) | implemented | `speed-v1` | pending |
| [Automatic splits](splits.md) | implemented | `splits-v1` | pending |
| [Synapse series](synapse-series.md) | implemented | `synapse-series-v1` | synthetic sessions only; nothing run on a band |

The device matrix and the rows are in
[../testing/device-matrix.md](../testing/device-matrix.md). Nothing in this
table is `stable` yet: the campaign has 0 of 56 rows filled.
