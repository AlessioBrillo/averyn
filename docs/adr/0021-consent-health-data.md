# ADR-0021: Consent for health-adjacent sensor data — server-authoritative, fail-closed device cache

- **Status:** proposed
- **Date:** 2026-10-18
- **Deciders:** @AlessioBrillo

## Context and problem

`data-classification.md` classes HRV, raw ECG/PPG, sleep stage, stress classification and other biosignal-derived metrics as **Health (special category)**; ADR-0013 requires a specific, revocable, per-scope consent record before processing, and ADR-0014 requires consent to gate the sensor adapter before streaming starts, not only before displaying results. Nothing in the codebase models consent yet: no table, no API, no device check.

The first data under this rule is RR intervals from a BLE heart-rate strap (ADR-0020 sidecar). HR bpm stays Restricted (no consent needed); RR needs `rr_recording` consent. Consent must be: server-authoritative (a phone app can't self-grant), revocable at any time (also from web), enforced on the device before recording starts (fail-closed: no known consent → no RR recorded), and revocation must delete already-collected RR data (sidecar + server object + tombstone) while the consent record itself is kept as audit evidence.

## Options considered

1. **Server `consents` table + API (`GET/POST/DELETE /v1/consents`), device caches and enforces fail-closed, revocation deletes RR data.** Matches ADR-0013/0014; survives app death; revoke-from-any-device works.
2. **Client-only consent (local preferences).** Simple, but a second device or the web app can't revoke, and the server can't enforce deletion — fails ADR-0013's server-side accountability.
3. **Server-only enforcement, no device cache.** A revoked user keeps recording RR offline until next sync — the window ADR-0013 wants closed ("stops processing new data under it immediately").

## Decision

**Option 1.**

- Backend table `consents(owner_id, scope, granted_at, revoked_at)`; scope `rr_recording` is the first (reserved: `hrv_analysis` for when `hrv-v1` lands). The row is never deleted with revocation (audit trail); only the data covered by the scope is.
- API `GET /v1/consents` (mine), `POST /v1/consents/{scope}` (grant), `DELETE /v1/consents/{scope}` (revoke), OIDC-authenticated, owner-only. Revoke answers `204` even when nothing was granted (idempotent).
- Device: on first use of a consent-gated adapter (RR streaming), fetches the grant from the server and caches it with the access token; fails closed — no cached/known grant → RR recording disabled, HR bpm still allowed (Restricted, no consent). On `401` the cache is cleared and the user is prompted to sign in again.
- Revocation propagates: the adapter stops recording RR at once (in-memory flag), the queued sidecar upload is cancelled, and already-uploaded RR objects are removed by a `deleted_rr` tombstone checked at ingest (`410` for a re-upload of that activity's RR sidecar). The activity itself is untouched.
- Export (`GET /v1/me/export`) includes consents and RR sidecars; account deletion (`DELETE /v1/me`) removes consents, RR objects and tombstones like any other user data (ADR-0017).

## Consequences

- Good: single source of truth, revoke-anywhere, offline-safe within one session.
- Cost: RR cannot be recorded before the first successful online grant fetch; the UI must explain this (wellness framing, no diagnostic claims — ADR-0013).
- Follow-up: `hrv_analysis` scope added with `hrv-v1`; DPIA stays a hard gate before any managed-cloud processing of Health-class data (ADR-0013).

## Revisit when

Before the first managed-cloud release processing Health-class data (DPIA gate), or when a second Health-class sensor scope is added.
