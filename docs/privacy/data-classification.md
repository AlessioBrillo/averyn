# Data classification

Status: v0.1. Reviewed on any change to collected or stored data (PR checklist item).

Sports data reveals home, workplace, routines, schedule, physical condition and indirectly health information ([report §12](../product/reference-report-v0.1.md)). Treat it accordingly.

| Class | Examples | Handling |
|---|---|---|
| **Health (special category)** | HRV, raw ECG/PPG, sleep stage, stress classification, and other biosignal-derived metrics from any sensor vendor | Everything Restricted requires, **plus**: a specific, revocable, per-scope consent record before processing ([ADR-0013](../adr/0013-health-data-special-category.md)); a DPIA before any managed-cloud processing; UI copy stays wellness framing, never diagnostic |
| **Restricted** | Raw location samples, routes, start/end points, heart rate, exact timestamps of activities, the files an activity was imported from, and the derived analysis of an activity (the splits, the elevation, the series) | Encrypted in transit and at rest; never in logs, analytics or error reports; access checked per object (IDOR tests); included in user export and hard delete |
| **Confidential** | Email, password hash, session tokens, device identifiers | Hashed/encrypted as appropriate; never logged; revocable |
| **Internal** | Aggregated operational metrics, job status, audit events | May be logged; contain ids and counts only |
| **Public** | Content the user explicitly made public (activity marked public, profile) | Subject to privacy zones and start/end hiding *before* exposure |

## Baseline rules

1. **Minimize:** collect only what a feature needs; new data types require a note in the PR.
2. **Private by default:** new activities default to private; visibility is per activity (public / followers / private / link).
3. **Privacy zones and start/end hiding** are applied server-side when serving any non-owner view — never trust the client to redact.
4. **Deletion is real:** account deletion removes raw files, samples and derived data immediately. Backups are the operator's: they are not rewritten, expire within the operator's documented retention period and are re-applied on restore ([ADR-0017](../adr/0017-data-deletion-and-retention.md)). Deleting one activity removes everything it owns: the canonical file, the file it was imported from, its heart-rate sidecar and its analysis.
5. **Export:** every user can export all their data in open formats, including the file an activity was imported from and the analysis of the activity.
6. **Telemetry:** off by default, opt-in, no location content ([ADR-0009](../adr/0009-observability-baseline.md)).
7. **Never log** coordinates, tokens, or user-generated free text.

## To be written

DPIA/GDPR notes before any public hosted service — the DPIA is a hard release gate specifically for Health-class data ([ADR-0013](../adr/0013-health-data-special-category.md)), not only a note.
