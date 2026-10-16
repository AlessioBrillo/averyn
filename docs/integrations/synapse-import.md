# Synapse health import — Averyn endpoint spec (day-1 lab path)

Status: proposed. Implements the lab import path (P1) from the SYNAPSE × Averyn
integration plan. Requires migration `V6__synapse_health_import.sql` (devices +
imported_health_samples). No BLE, no provisioning, no worker needed for day 1.

## Principle

Averyn is the system of record (ADR-0010). The user uploads a FIT file exported
from a SYNAPSE-24 session (`scripts/export_session_fit.py` in the SYNAPSE-24
repo). Averyn parses the Synapse developer fields, stores each record as an
owner-scoped `imported_health_samples` row with first-class provenance
(`source`, `algorithm_version`), and exposes it through a read endpoint with a
visible "via Synapse" label. Consent per scope (ADR-0013) is required before
the endpoint can store HRV/stress/sleep data.

## Endpoints

### POST /v1/health/import

Auth: JWT (bearer). Rate limit: per-user 120 req/min (existing middleware).
Body: `multipart/form-data`, field `file` (`.fit`), max 32 MB (existing upload
limits apply).

Fields on the FIT (flat scalars, per the canonical table):
- `synapse_stress` uint8: 0=baseline, 1=stress, 2=artifact, 3=unknown
- `synapse_sqi` uint8: 0-255 PPG signal quality
- `synapse_map` uint8: 0-255 motion artifact probability
- `synapse_rmssd` uint16: ms
- `synapse_sdnn` uint16: ms
- `synapse_lf_hf` uint16: ×100
- `synapse_ecg_quality` uint8: 0-255
- `synapse_sleep_stage` uint8: 0=W..4=REM (only if EEG input; otherwise absent)

Query params (optional):
- `device_serial`: bind to an existing `devices` row by `vendor_serial`.
  If the device does not exist, it is auto-created with kind `synapse-band`.
- `activity_id`: associate samples with an existing activity (must belong to
  the caller). Omit for overnight/standalone samples.

Response 200:
```json
{
  "imported": 60,
  "source": "fit-file",
  "algorithm_version": "export_session_fit-v1",
  "device_id": "...",
  "records": [
    {"recorded_at": "...", "kind": "hrv", "value_json": {...}}
  ]
}
```

### GET /v1/health/summary

Auth: JWT. Query params: `from`, `to` (timestamptz), `kind` (optional).

Response: list of samples grouped by day/kind, each with `source` and
`algorithm_version` so the UI can render "via Synapse" provenance.

## Consent

Before storing any sample with `kind` in {hrv, stress, sleep, sqi}, the
backend checks that the caller has a current grant for the matching scope
(`hrv_analysis`, `stress_monitoring`, `sleep_staging`, `signal_quality`).
Missing grant → 403 `Consent required for <scope>`. The grant UI is tracked
separately (ADR-0013); until it exists, the endpoint returns 403 for health
kinds (day-1 remains local-only / demo).

## Deletion & export

Extend `UserData.kt` (`deleteAccount` + export manifest) to:
- delete `imported_health_samples` rows owned by the user
- delete `devices` rows owned by the user
- include imported samples in the export ZIP manifest

(ADR-0017 data-deletion pattern already exists for activities.)

## What this does NOT do (by design)

- No BLE / live HR (that is MVP-2 + generic sensor support).
- No provisioning / OTA / cloud device registration (Synapse side, day-1).
- No `shared/metrics` types for Synapse values (ADR-0014: imported only).
- No readiness score (needs sleep+stress+load; not day-1).
