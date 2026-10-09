# Roadmap

Rule: **vertical slices, GPS validated on real devices first.** The biggest risk is building tracking, analysis, maps, social, watch, self-hosting and cloud at the same time ([report §26](reference-report-v0.1.md)). Each MVP is a GitHub milestone; issues carry `area/*` labels.

| MVP | Goal | Exit criteria |
|---|---|---|
| **MVP-0** Tracking PoC | Prove native tracking is reliable | Record ≥ 3 h outdoor activities on ≥ 2 real Android and ≥ 2 real iOS devices with screen locked; force-kill and reboot recovery loses ≤ 1 sample interval; GPX export validates; diagnostics report shows quality; results logged in the device matrix |
| **MVP-1** Core | Account, sync, backend, web detail page | Activity recorded offline syncs idempotently and is visible on the web with map + basic metrics; self-host via Compose works from a clean machine |
| **MVP-2** Analysis & interop | Import/export and analysis | GPX/FIT/TCX import with duplicate detection; charts, elevation, laps, PRs; BLE HR; HealthKit / Health Connect; versioned metrics |
| **MVP-3** Maps & navigation | Route editor, offline maps | Pilot-region routing, offline track following, deviation alerts |
| **MVP-4** Social & segments | Community | Profiles, follow, feed, privacy zones, segments, leaderboards, moderation |

Later (Phase 6): standalone smartwatch, training load / fitness-fatigue, challenges, events, public API and plugins, club features, automated cloud deployment.

## Synapse track

Optional third-party sensor integration, see [ADR-0010](../adr/0010-ecosystem-boundary.md), [ADR-0014](../adr/0014-sensor-integration-contract.md) and the [integration contract](../integrations/synapse.md). Sequenced after its dependencies, not in parallel with them — the strategy input's own sequencing put this before MVP-1's account/sync work it depends on ([review](../strategy/review-2026-09-27.md) #19):

| When | What |
|---|---|
| Now | Contracts only (this roadmap entry, the ADRs, the integration doc). No code. |
| MVP-2 | Generic BLE Heart Rate support lands with **zero** Synapse-specific code — any standard-compliant strap or the Synapse device (once its firmware exposes the standard service) works identically. |
| After MVP-1 (accounts/sync) and alongside MVP-2 | Synapse-specific native adapter (compressed-feature characteristic) and FIT import fields, gated by per-scope consent ([ADR-0013](../adr/0013-health-data-special-category.md)). |
| After that | Inference worker job/result plumbing (ADR-0010), only once there's a real, contract-conformant firmware to integrate against — `SYNAPSE-24` does not have one yet (see the review). |

## MVP-1 work order

Critical path: identity spike → build so the backend can use `shared/` → read-only replay → OIDC auth → activity ingest → `shared/sync` → app login and sync trigger. Spec: [TDD-0002](../design/TDD-0002-activity-sync.md). Done: web activity list and detail page, [TDD-0003](../design/TDD-0003-web-activity-view.md). Next: data deletion and export, [TDD-0004](../design/TDD-0004-data-deletion-and-export.md) / [ADR-0017](../adr/0017-data-deletion-and-retention.md); then self-host hardening (TLS, CSP, per-user quota). Real-device runs (MVP-0 exit criteria, device-matrix S1–S14) stay open and take priority over MVP-2.

## MVP-0 work order

From [report §22](reference-report-v0.1.md), steps 1–9: requirements → sample model → iOS adapter → Android adapter → local storage → pause/resume/recovery → base metrics → fixtures and tests → real-device validation. Spec: [TDD-0001](../design/TDD-0001-tracking-engine.md).

## Open decisions

Tracked here until they become ADRs (report §24). ● = needed for MVP-0.

- ~~Raw sample internal format and storage; sampling frequency; GPS filtering strategy~~ → resolved, [TDD-0001](../design/TDD-0001-tracking-engine.md), [ADR-0015](../adr/0015-local-activity-store.md)
- ~~Mobile local database~~ → resolved, append-only file not a DB, [ADR-0015](../adr/0015-local-activity-store.md)
- ~~Sports supported in MVP~~ → resolved: run, ride, walk, hike, [TDD-0001 §5.3](../design/TDD-0001-tracking-engine.md)
- Final project name → [ADR-0008](../adr/0008-project-codename.md)
- ~~CLA vs DCO~~ → resolved, [ADR-0012](../adr/0012-cla-and-store-distribution.md)
- ~~Default bundled IdP~~ → resolved: Zitadel, [ADR-0011](../adr/0011-identity-oidc.md); passkeys/MFA timing still open
- Map data source, routing engine (Valhalla vs. GraphHopper), DEM source, pilot region
- Cloud provider; notification strategy; moderation policy (retention of user data: resolved, [ADR-0017](../adr/0017-data-deletion-and-retention.md))
- Fair-use quota thresholds and club/team plan pricing → [business-model.md](business-model.md)
- Device compatibility level (watches, sensors) beyond generic BLE HR → see the Synapse track above for the one integration currently planned
