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

## MVP-0 work order

From [report §22](reference-report-v0.1.md), steps 1–9: requirements → sample model → iOS adapter → Android adapter → local storage → pause/resume/recovery → base metrics → fixtures and tests → real-device validation. Spec: [TDD-0001](../design/TDD-0001-tracking-engine.md).

## Open decisions

Tracked here until they become ADRs (report §24). ● = needed for MVP-0.

- ● Raw sample internal format and storage; sampling frequency; GPS filtering strategy → TDD-0001
- ● Mobile local database (candidates: SQLDelight, Room + GRDB) → TDD-0001
- ● Sports supported in MVP (proposal: run, ride, walk, hike)
- Final project name → [ADR-0008](../adr/0008-project-codename.md)
- CLA vs DCO → [ADR-0002](../adr/0002-license-agplv3.md)
- Authentication system (own vs. OIDC provider; passkeys/MFA timing)
- Map data source, routing engine (Valhalla vs. GraphHopper), DEM source, pilot region
- Cloud provider; notification strategy; retention policy; monetization model; moderation policy
- Device compatibility level (watches, sensors)
