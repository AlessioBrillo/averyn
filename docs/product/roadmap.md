# Roadmap

Rule: **vertical slices, GPS validated on real devices first.** The biggest risk is building tracking, analysis, maps, social, watch, self-hosting and cloud at the same time ([report §26](reference-report-v0.1.md)). Each milestone is a GitHub milestone; issues carry `area/*` labels. No code lands without an issue in the current milestone, and a spec without code and tests in the same increment stays `draft` and off this page.

| Milestone | Goal | Exit criteria |
|---|---|---|
| **MVP-0** Tracking PoC | Prove native tracking is reliable | Record ≥ 3 h outdoor activities on ≥ 2 real Android and ≥ 2 real iOS devices with screen locked; force-kill and reboot recovery loses ≤ 1 sample interval; GPX export validates; diagnostics report shows quality; results logged in the device matrix |
| **MVP-1** Core | Account, sync, backend, web detail page | Activity recorded offline syncs idempotently and is visible on the web with map + basic metrics; self-host via Compose works from a clean machine |
| **MVP-2** Analysis & interop | Import/export and analysis | GPX/FIT/TCX import with duplicate detection; charts, elevation, laps, PRs; BLE HR; HealthKit / Health Connect; versioned metrics |
| **MVP-L** Launch | A free, privacy-first public release built around three moments, proved on real devices ([ADR-0028](../adr/0028-consumer-launch-target.md)) | See [below](#mvp-l-launch) and the live checklist in [mvp-launch.md](mvp-launch.md) |
| **MVP-3** Maps & navigation | Route editor, offline maps | Pilot-region routing, offline track following, deviation alerts |
| **MVP-4** Social & segments | Community | Profiles, follow, feed, segments, leaderboards, moderation (privacy zones moved to MVP-L) |

Later (Phase 6): standalone smartwatch, training load / fitness-fatigue, challenges, events, public API and plugins, club features, automated cloud deployment.

## MVP-L: launch

MVP-L is the first public release: **a free, privacy-first app that is good to use**, built from the slices of MVP-0 to MVP-2 plus what the three moments need ([ADR-0028](../adr/0028-consumer-launch-target.md), which replaced the earlier "managed-cloud, 13 weeks, company" framing). There is **no fixed date**; the order of work and the cut line are the control. One developer. The apps go to the stores under the owner's own name; the public name is the owner's decision before the listing ([ADR-0008](../adr/0008-project-codename.md)).

Exit criteria (each one is verifiable; the checklist with owners and issues is [mvp-launch.md](mvp-launch.md)):

1. **Reliability.** The device matrix is green on ≥ 2 Android and ≥ 2 iOS devices with a reference HR strap: S1–S28, and the row of every launch feature (S32–S41, S42–S48). No feature is `verified` before its row is green.
2. **The three moments.** Recording (readable, voice cues), the summary right after (with a share card), history and progress (weeks, months, records, calendar). A design system is applied to them on both phones, approved by the owner on mockups first.
3. **Privacy you can see.** Recording and viewing work with no account; private zones hide the start and the end in every view and in the card; export and deletion are in the app.
4. **Data in and out.** GPX and FIT import (single files and an exported archive), full export, write-only HealthKit / Health Connect.
5. **Sensors.** Standard BLE HR on both platforms (a Synapse band works as a plain strap, level L0). The Synapse feature frame stays a beta that starts when the prototype exists.
6. **Managed cloud.** Open sign-up with email verification, an EU instance, off-site backups proven by a restore, monitoring and alerting, per-account quota.
7. **Legal and stores.** Privacy policy and terms, Apple privacy label, Play data safety, in-app account deletion, store accounts (personal), the closed test that a new personal Play account requires.
8. **Quality.** The checks of the [CI gate](../../CLAUDE.md) are green on the final tree, the iOS job and the signed-in round trip included.

Out of MVP-L: TCX, reading from HealthKit / Health Connect, the Synapse inference worker, laps, offline maps and navigation (MVP-3), social and segments (MVP-4).

**Cut line**, in this order, if the effort slips: Synapse L1 → voice cues → archive import (single FIT stays) → heart-rate zones. **Never cut:** the device campaign, the three moments, privacy zones, in-app account deletion, backup and restore, the legal texts.

### What is done

Everything below is **code in place, not verified on a device**: the device matrix has no filled row, so none of it is "done".

- **MVP-1 code:** sync, identity, web list and detail ([TDD-0002](../design/TDD-0002-activity-sync.md), [TDD-0003](../design/TDD-0003-web-activity-view.md)), data deletion and export ([TDD-0004](../design/TDD-0004-data-deletion-and-export.md)), self-host hardening ([TDD-0005](../design/TDD-0005-self-host-hardening.md)), field readiness ([TDD-0006](../design/TDD-0006-field-readiness.md)).
- **MVP-2 slices:** heart-rate channel with consent-gated RR ([TDD-0007](../design/TDD-0007-sensor-channels.md)), GPX import ([TDD-0008](../design/TDD-0008-gpx-import.md)), analysis ([TDD-0009](../design/TDD-0009-activity-analysis.md)), personal records ([TDD-0010](../design/TDD-0010-best-efforts.md)), the read path on the phone ([TDD-0011](../design/TDD-0011-mobile-read-path.md)).
- **Launch slices already built:** the profile, heart-rate zones and phone map ([TDD-0012](../design/TDD-0012-profile-zones-phone-map.md)); FIT and archive import ([TDD-0013](../design/TDD-0013-fit-import.md)); HealthKit and Health Connect write ([TDD-0014](../design/TDD-0014-health-platforms.md)); account deletion on the phones; IT/EN; legal links; backup and restore with a drill in CI; a DPIA draft and an incident runbook; the Synapse frame parser and the pinned contract.

### Work order

| Inc | What | Notes |
|---|---|---|
| 1 | Campaign and builds: tester builds by hand (APK; iOS from the Mac), S1–S28, the fixes | Starts now, runs in parallel with everything; a red row in the tracking stops new features |
| 2 | Design system and mockups for the three moments, onboarding and settings | The owner approves before any screen is built; design doc `TDD-0015` |
| 3 | Experience on Android | Navigation (Record / Activities / Progress / Settings), theme tokens, the server address under "Advanced" |
| 4 | Experience on iOS, with a test target | Same structure |
| 5 | Progress: weekly and monthly totals, calendar | Computed in the shared client from the history, no new endpoint |
| 6 | Privacy zones | Display-only cut, raw samples untouched; own design doc |
| 7 | Share card | Made on the phone, shared by the user's tap |
| 8 | Voice cues | Per-kilometre events from the tracking engine; text-to-speech on both phones |
| 9 | Onboarding and accessibility | Permission explanations, pairing of a strap or band, large text, screen readers |
| 10 | Cloud, legal texts, store listings | Provider, SMTP, open sign-up; closed test on Play; the name |
| 11 | Synapse L1 beta | When the prototype arrives |

## Synapse track

Optional third-party sensor integration. The rules are [ADR-0010](../adr/0010-ecosystem-boundary.md), [ADR-0014](../adr/0014-sensor-integration-contract.md) and [ADR-0026](../adr/0026-averyn-synapse-link.md); the integration contract is [synapse.md](../integrations/synapse.md). Averyn is the only system of record; Synapse is a BLE sensor and a research SDK.

| Level | What | Averyn code | When |
|---|---|---|---|
| L0 | The band as a standard HR strap (`0x180D`/`0x2A37` with RR, Battery, Device Information) | none | When the prototype arrives: matrix row S15 with the band next to the reference strap |
| L1 | Feature frame v1 from the consumer service (SQI, motion artifact, stress class; RMSSD/SDNN once real) | pure parser in `shared/sensors` (**done**, with vectors generated from the packed struct), native adapters and a consent-gated sidecar (wait for a prototype and for the handoff in the Synapse repository) | MVP-L, beta, first on the cut line |
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
