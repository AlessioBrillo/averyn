# TDD-0017: The band's signals on the activity page

- **Status:** draft (server, shared code and web page implemented with tests; the phone side, the mobile screens and every check on a real band, phone and browser wait)
- **Version:** 0.1
- **Author:** @AlessioBrillo
- **Date:** 2026-11-07
- **Related:** [ADR-0030](../adr/0030-synapse-contract-v2-1-and-raw-signals.md), [ADR-0021](../adr/0021-consent-health-data.md), [`synapse.md`](../integrations/synapse.md), [`synapse-series`](../metrics/synapse-series.md), [TDD-0015](TDD-0015-consumer-ux.md)

## 1. Summary

A Synapse band measures more than a heart-rate strap: derived values at about 1 Hz and, with a separate consent, raw ECG, pulse wave and motion signals. Every view of them lives in Averyn (the device repository ships no viewer). This TDD describes the path from the sidecars to the page: how they are stored, what the server derives, what the browser draws and how a revocation deletes.

## 2. Goals and non-goals

Goals: show the derived values and the raw signals of an activity, zoomable from the whole recording down to a single beat; show every loss as data, with its reason; make a revocation delete what it covers; keep every chart readable without the chart.

Non-goals: a live-signals screen, the Android and iOS history views, the phone's adapters and sidecar writer, any filtering or diagnosis of the signals, a second device, a persisted pyramid.

## 3. Functional requirements

- F1: the sidecars are stored as received, one object per activity and kind, and count toward the quota.
- F2: `GET .../synapse` returns what was recorded; `GET .../synapse/raw/{stream}` returns a window as min/max buckets per run without a hole, in the contract's display unit.
- F3: no consent, no data: a kind needs its own consent to be stored and again to be read.
- F4: revoking `signal_quality` or `raw_signals` deletes the sidecars and leaves a tombstone; revoking `stress_monitoring`, `rr_recording` or `hrv_analysis` rewrites the stored frames with those fields "not available".
- F5: a gap is a break in the line and a row in a table; a field the band did not give is `null`, never zero.
- F6: every chart has a table (the per-signal account and the gaps); the page says "via Synapse" and that the signals are not a medical record.

## 4. Non-functional requirements

Privacy: the raw ECG is the most identifying stream a band has and has its own scope; the owner is the only reader; the sidecars live under the activity's prefix, so deleting the activity or the account removes them. Size: about 2.4 kB/s for the raw streams (35 MB for four hours), capped at 48 MB; a window never returns more than 4000 buckets per run. Self-hosting: no new service; the object store and the database are the existing ones.

## 5. Design

- **Storage.** `activity_synapse_sidecars (activity_id, kind, object_key, sha256, size_bytes)` with `ON DELETE CASCADE` from the activity; objects under `raw/{owner}/{clientActivityId}/synapse/{kind}/{sha}.bin`; a `deleted_synapse` tombstone per (owner, client activity, kind) so a device that still has a revoked sidecar cannot upload it again (`410`).
- **Reading.** `SynapseRecording` decodes a raw sidecar with the `shared/sensors` reader and groups the samples in segments; levels of the min/max pyramid are built on demand and cached with the recording (four recordings, by checksum). `buildFeatures` turns the feature sidecar into parallel arrays and applies the consents again.
- **Page.** `SynapseSignals` loads the summary, draws the derived values with the page's existing chart component, and the raw signal as a band (maximum and minimum, filled between) with zoom and pan that ask the server for the new window. The browser decodes nothing and writes no unit.
- **Failure modes.** A damaged sidecar is read up to its last whole record; a stream that was not recorded answers `404`; a window outside the recording is empty; an expired session is a `401` and the page offers sign-in as everywhere else.

## 6. Test strategy

`shared/sensors`: the parsers against the golden vectors and the replay of the device repository's sessions against its answer key. `backend`: the recording, the series and the masking as pure tests on the 60 s session; the routes on real PostgreSQL and S3 (consent, tombstone, masking, quota, isolation between users, cascade). `apps/web`: the series, band, zoom and pan as pure functions. Device matrix: S29–S31, S49–S50 stay **empty** until a band is used.

## 7. Rollout and migration

Migration `V14` adds two tables and nothing else. No data exists before the first upload, so nothing is migrated. The scopes are granted on the account page. Until the phone writes the sidecars, the only data the page can show is the device repository's synthetic sessions.
