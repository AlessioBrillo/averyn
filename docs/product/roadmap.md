# Roadmap

Rule: **vertical slices, GPS validated on real devices first.** The biggest risk is building tracking, analysis, maps, social, watch, self-hosting and cloud at the same time ([report §26](reference-report-v0.1.md)). Each milestone is a GitHub milestone; issues carry `area/*` labels. No code lands without an issue in the current milestone, and a spec without code and tests in the same increment stays `draft` and off this page.

| Milestone | Goal | Exit criteria |
|---|---|---|
| **MVP-0** Tracking PoC | Prove native tracking is reliable | Record ≥ 3 h outdoor activities on ≥ 2 real Android and ≥ 2 real iOS devices with screen locked; force-kill and reboot recovery loses ≤ 1 sample interval; GPX export validates; diagnostics report shows quality; results logged in the device matrix |
| **MVP-1** Core | Account, sync, backend, web detail page | Activity recorded offline syncs idempotently and is visible on the web with map + basic metrics; self-host via Compose works from a clean machine |
| **MVP-2** Analysis & interop | Import/export and analysis | GPX/FIT/TCX import with duplicate detection; charts, elevation, laps, PRs; BLE HR; HealthKit / Health Connect; versioned metrics |
| **MVP-L** Launch | A public managed-cloud launch, built from the slices of MVP-0 to MVP-2 | See [below](#mvp-l-launch) and the live checklist in [mvp-launch.md](mvp-launch.md) |
| **MVP-3** Maps & navigation | Route editor, offline maps | Pilot-region routing, offline track following, deviation alerts |
| **MVP-4** Social & segments | Community | Profiles, follow, feed, privacy zones, segments, leaderboards, moderation |

Later (Phase 6): standalone smartwatch, training load / fitness-fatigue, challenges, events, public API and plugins, club features, automated cloud deployment.

## MVP-L: launch

MVP-L replaces "finish MVP-2 whole" as the target. It takes the slices of MVP-0 to MVP-2 that a public managed-cloud launch needs, and nothing else. Target: ~13 weeks from 2026-10-26, one developer, with an explicit cut line. The company that runs the managed service (the GDPR controller) is an SRL; the public name is still open ([ADR-0008](../adr/0008-project-codename.md)) and is a gate of the store listing.

Exit criteria (each one is verifiable; the checklist with owners and issues is [mvp-launch.md](mvp-launch.md)):

1. **Reliability.** The device matrix S1–S28 is green on ≥ 2 Android and ≥ 2 iOS devices, with a reference HR strap. No feature is "done" before its row is green.
2. **Core loop.** Record offline → sync → phone and web show list, detail, **map**, analysis and records; a minimal profile (units, maximum heart rate, zones).
3. **Data in and out.** GPX and FIT import (single files and an exported archive), full export (done), write-only HealthKit / Health Connect.
4. **Sensors.** Standard BLE HR on both platforms (a Synapse band works as a plain strap); the Synapse feature frame in **beta**, opt-in and consent-gated.
5. **Managed cloud.** Open sign-up with email verification, an EU instance, off-site backups proven by a restore, monitoring and alerting, quotas.
6. **Legal.** The company exists, a DPIA is written (RR intervals are already health data), privacy policy, terms of service, Apple privacy label, Play data safety, in-app account deletion.
7. **Quality.** The gate is green on the final tree, the iOS job included, plus an end-to-end job against the real identity provider.

Out of MVP-L: TCX, reading from HealthKit / Health Connect, the Synapse inference worker, laps, offline maps and navigation (MVP-3), social (MVP-4).

**Cut line**, in this order, if the schedule slips: Synapse feature frame → HealthKit / Health Connect → archive import (single FIT stays) → heart-rate zones. **Never cut:** the device campaign, the DPIA and legal work, in-app account deletion, backup and restore, a green gate.

### What is done

- **MVP-1 code:** sync, identity, web list and detail ([TDD-0002](../design/TDD-0002-activity-sync.md), [TDD-0003](../design/TDD-0003-web-activity-view.md)), data deletion and export ([TDD-0004](../design/TDD-0004-data-deletion-and-export.md)), self-host hardening ([TDD-0005](../design/TDD-0005-self-host-hardening.md)), field readiness ([TDD-0006](../design/TDD-0006-field-readiness.md)).
- **MVP-2 slices:** heart-rate channel with consent-gated RR ([TDD-0007](../design/TDD-0007-sensor-channels.md), Android and iOS adapters), GPX import ([TDD-0008](../design/TDD-0008-gpx-import.md)), analysis ([TDD-0009](../design/TDD-0009-activity-analysis.md)), personal records ([TDD-0010](../design/TDD-0010-best-efforts.md)), the read path on the phone ([TDD-0011](../design/TDD-0011-mobile-read-path.md)).
- **Not verified on a device:** all of it. The [device matrix](../testing/device-matrix.md) is empty, so every item above is "code in place", not "done".

### Work order

| Inc | Weeks | What |
|---|---|---|
| 0 | 1 | Realignment: green iOS build, consent refresh on iOS, CI coverage, docs, ADR-0026 and ADR-0027 |
| 1 | 1–2 | Tester builds (TestFlight, Play internal) and the start of the campaign |
| 2 | 2–4 | Profile (units, max HR, zones) and the map on the phone |
| 3 | 4–6 | FIT import and archive import |
| 4 | 6–8 | In-app account deletion, onboarding, IT/EN, legal pages on the web |
| 5 | 7–9 | HealthKit / Health Connect (write only) |
| 6 | 9–10 | Synapse feature frame, beta (first to cut) |
| 7 | 8–11 | Managed cloud and a closed beta of 10–20 testers |
| 8 | 10–13 | DPIA, privacy policy, terms, store listings, submission, launch |

The campaign (Inc 1 onwards) runs in parallel with all of them.

## Synapse track

Optional third-party sensor integration. The rules are [ADR-0010](../adr/0010-ecosystem-boundary.md), [ADR-0014](../adr/0014-sensor-integration-contract.md) and [ADR-0026](../adr/0026-averyn-synapse-link.md); the integration contract is [synapse.md](../integrations/synapse.md). Averyn is the only system of record; Synapse is a BLE sensor and a research SDK.

| Level | What | Averyn code | When |
|---|---|---|---|
| L0 | The band as a standard HR strap (`0x180D`/`0x2A37` with RR, Battery, Device Information) | none | When the prototype arrives: matrix row S15 with the band next to the reference strap |
| L1 | Feature frame v1 from the consumer service (SQI, motion artifact, stress class; RMSSD/SDNN once real) | pure parser in `shared/sensors`, native adapters, a consent-gated sidecar | MVP-L, beta, first on the cut line |
| L2 | `synapse_*` FIT developer fields read by the FIT import as imported data | field mapping in the FIT decoder | With the FIT import if time allows, otherwise after launch |
| L3 | Job/result plumbing to a stateless worker (HRV trends, daily stress) | queue and per-job credentials | After launch, after the DPIA covers it |
| — | The research raw-stream service (ECG 500 Hz) | never | Averyn does not subscribe to it |

## MVP-0 and MVP-1 gates still open

Real-device runs (device matrix S1–S28) take priority over any new feature. [TDD-0006](../design/TDD-0006-field-readiness.md) fixed the defects an audit found on 2026-10-15; what is left is the campaign itself, the tester distribution ([ADR-0019](../adr/0019-crash-reporting-and-tester-builds.md)) and an end-to-end CI job against the real identity provider (TDD-0006 Q3, Q4).

MVP-0 work order, from [report §22](reference-report-v0.1.md): requirements → sample model → iOS adapter → Android adapter → local storage → pause/resume/recovery → base metrics → fixtures and tests → real-device validation. Spec: [TDD-0001](../design/TDD-0001-tracking-engine.md).

## Open decisions

Tracked here until they become ADRs (report §24).

- ~~Raw sample internal format and storage; sampling frequency; GPS filtering strategy~~ → resolved, [TDD-0001](../design/TDD-0001-tracking-engine.md), [ADR-0015](../adr/0015-local-activity-store.md)
- ~~Mobile local database~~ → resolved, append-only file not a DB, [ADR-0015](../adr/0015-local-activity-store.md)
- ~~Sports supported in MVP~~ → resolved: run, ride, walk, hike, [TDD-0001 §5.3](../design/TDD-0001-tracking-engine.md)
- **The gap policy of the distance** (`distance-v2`): the metric has none, and the best efforts inherit the limit. It is not guessed: the real-device campaign produces the data that says how long a silence has to be before it is a gap. Until then, the best efforts break on a silence of their own.
- **Final public project name** (the technical identifiers are fixed) → [ADR-0008](../adr/0008-project-codename.md). Gate of the store listing, to decide by week 10.
- ~~CLA vs DCO~~ → resolved, [ADR-0012](../adr/0012-cla-and-store-distribution.md)
- ~~Default bundled IdP~~ → resolved: Zitadel, [ADR-0011](../adr/0011-identity-oidc.md); passkeys/MFA timing still open
- ~~Cloud provider, map tile source for the managed cloud~~ → proposed, [ADR-0027](../adr/0027-managed-cloud-baseline.md)
- Routing engine (Valhalla vs. GraphHopper), DEM source, pilot region (MVP-3)
- Notification strategy; moderation policy (retention of user data: resolved, [ADR-0017](../adr/0017-data-deletion-and-retention.md))
- Fair-use quota thresholds and club/team plan pricing → [business-model.md](business-model.md)
- Device compatibility beyond generic BLE HR → the Synapse track above
