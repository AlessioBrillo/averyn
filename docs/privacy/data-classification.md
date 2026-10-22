# Data classification

Status: v0.1. Reviewed on any change to collected or stored data (PR checklist item).

Sports data reveals home, workplace, routines, schedule, physical condition and indirectly health information ([report §12](../product/reference-report-v0.1.md)). Treat it accordingly.

| Class | Examples | Handling |
|---|---|---|
| **Health (special category)** | HRV, raw ECG/PPG, sleep stage, stress classification, and other biosignal-derived metrics from any sensor vendor | Everything Restricted requires, **plus**: a specific, revocable, per-scope consent record before processing ([ADR-0013](../adr/0013-health-data-special-category.md)); a DPIA before any managed-cloud processing; UI copy stays wellness framing, never diagnostic |
| **Restricted** | Raw location samples, routes, start/end points, heart rate, exact timestamps of activities, the files an activity was imported from, and the derived analysis of an activity (the splits, the elevation, the series, the best efforts) and of an account (the personal records). The copy of the server on the device (the routes and the series of the activities that were opened) and the local summary of an activity (its numbers, no coordinates) are Restricted too | Encrypted in transit and at rest; never in logs, analytics or error reports; access checked per object (IDOR tests); included in user export and hard delete |
| **Confidential** | Email, password hash, session tokens, device identifiers | Hashed/encrypted as appropriate; never logged; revocable |
| **Internal** | Aggregated operational metrics, job status, audit events | May be logged; contain ids and counts only |
| **Public** | Content the user explicitly made public (activity marked public, profile) | Subject to privacy zones and start/end hiding *before* exposure |

## Baseline rules

1. **Minimize:** collect only what a feature needs; new data types require a note in the PR.
2. **Private by default:** new activities default to private; visibility is per activity (public / followers / private / link).
3. **Privacy zones and start/end hiding** are applied server-side when serving any non-owner view — never trust the client to redact.
4. **Deletion is real:** account deletion removes raw files, samples and derived data immediately. Backups are the operator's: they are not rewritten, expire within the operator's documented retention period and are re-applied on restore ([ADR-0017](../adr/0017-data-deletion-and-retention.md)). Deleting one activity removes everything it owns: the canonical file, the file it was imported from, its heart-rate sidecar and its analysis — and the record of the account moves to the next activity by itself ([ADR-0024](../adr/0024-personal-records.md)).
5. **Export:** every user can export all their data in open formats, including the file an activity was imported from and the analysis of the activity.
6. **Telemetry:** off by default, opt-in, no location content ([ADR-0009](../adr/0009-observability-baseline.md)).
7. **Never log** coordinates, tokens, or user-generated free text.
8. **A copy on the device has a life of its own:** the copy of the server on the device is bounded, is excluded from the backup, ends with the session and can be deleted by the user at any time ([ADR-0025](../adr/0025-device-side-cache-of-server-activities.md)). It never holds a token, a raw file or an interval RR.

## To be written

DPIA/GDPR notes before any public hosted service — the DPIA is a hard release gate specifically for Health-class data ([ADR-0013](../adr/0013-health-data-special-category.md)), not only a note.
