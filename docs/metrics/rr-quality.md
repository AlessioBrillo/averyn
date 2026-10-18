# Metric: RR interval quality

- **Status:** draft
- **Current algorithm version:** `rr-quality-v1`

## Definition

Quality artifacts counted over raw RR intervals (ms between consecutive heart beats) collected with `rr_recording` consent (ADR-0021). This is **not** an HRV score: RMSSD/SDNN/sleep staging require rest windows and controlled posture, which activity recording does not provide (see docs/strategy/review-2026-09-27.md #6 — a PPG/IMU-only heuristic must not be called "YASA sleep staging", and the same honesty applies to HRV). Artifacts are counted, stored as `rr-quality-v1`, and exposed only as counted diagnostics.

## Unit

Counts and percentages; RR values in ms.

## Formula / method

`rr-quality-v1`: over every `RrInterval` record in the sidecar:

- `rr_count` — total intervals stored
- `rr_implausible` — intervals `< 300 ms` or `> 2000 ms` (physiologically impossible at rest or in exercise)
- `rr_missed` — gaps larger than 2 s against the expected 1 s grid (a beat the strap did not report)
- `rr_median_deviation_pct` — share of intervals deviating more than 25 % from the rolling local median (window 30 s)
- `rr_coverage_percent = 100 × rr_count / expected intervals` (expected = activity duration in seconds)
- `rr_artifacts = rr_implausible + rr_missed` (also reported per-1000 intervals)

No interpolation; no score; no medical claim (wellness framing, ADR-0013).

## Required inputs

`RrInterval` records (`rrMs`) with `elapsedRealtimeMs`, sidecar `<id>.rr.jsonl` (ADR-0020).

## Minimum quality requirements

Collected only under `rr_recording` consent; adapter fails closed without it. `contact` lost (sensor off skin) intervals are still stored but flagged in `rr_artifacts` via the adapter's contact flag where the strap reports it.

## Missing-data handling

Missing RR are `rr_missed`, not interpolated. A sidecar that stops mid-activity reports coverage < 100 and the gap is visible in diagnostics, never silently filled.

## Version history

| Version | Date | Change |
|---|---|---|
| rr-quality-v1 | 2026-10-18 | Initial: counted artifacts, coverage, no score |

## Reliability level

Estimate. Strap quality and placement dominate; treated as diagnostic signal only.

## Known limitations

No HRV-derived scores; no resting-vs-activity stratification yet; contact flag depends on the strap exposing it.

## Reference tests

`shared/sensors`/`shared/metrics` unit tests with synthetic RR fixtures; device matrix S15 validates against a reference strap.
