# MVP-L: launch checklist

Status: living document, rewritten 2026-10-29 for [ADR-0028](../adr/0028-consumer-launch-target.md). The goal and the exit criteria are in the [roadmap](roadmap.md#mvp-l-launch); this page is the checklist behind them. A row is closed when its evidence exists, not when its code merges. There is no fixed date; the estimates are in weeks of work for one developer.

Legend: ✅ in place · 🟡 partly · ⬜ not started · 🔒 needs something only the owner can do.

## Gap analysis

| Area | State | Missing | Estimate | Evidence that closes it |
|---|---|---|---|---|
| Device validation | 🟡 protocol ready, **0 of 48 scenarios run** | builds on the test devices; S1–S28 and the launch rows on ≥ 2 Android and ≥ 2 iPhone with a reference strap; the fixes the campaign finds; `distance-v2` from the data | 3–4 weeks, in parallel | Filled rows in the [device matrix](../testing/device-matrix.md) |
| Experience: recording | ⬜ the main screen of the Android app is a server field and a column of buttons | design system and mockups; a readable recording screen; voice cues | 3 weeks with the summary | S44, S47 |
| Experience: summary | 🟡 detail, splits, curves, map exist | a summary right after the activity; the share card | in the same 3 weeks | S43 |
| Experience: history and progress | 🟡 list, offline copy, records exist | weekly / monthly totals, a calendar, a clearer list | 2 weeks | S45 |
| Privacy features | 🟡 deletion, export, consents exist | private zones (display-only cut) on phones, web and card; recording and viewing with no account; a visible "what is stored" page | 2 weeks | S42, S46 |
| Onboarding and accessibility | ⬜ | permission explanations, pairing of a strap or band, large text, screen readers | 1 week | S46, S48 |
| Distribution | 🟡 Android signing wired, never run; iOS manual | by hand first (APK; iOS from the Mac, 7-day provisioning); then store accounts 🔒 (personal), Play internal, TestFlight, signing team in `project.yml` | 2–3 days + store waits (new personal Play account: closed test, 12 testers, 14 days) | A build installed from each store |
| CI | 🟡 two pipelines; GitHub green except iOS history; Woodpecker runs on PRs but has no iOS, compose or round-trip job | iOS build kept green, shared tests that run on iOS (today most are `jvmTest`: `tracking` 1 of 12, `client` 0 of 6, `sync` 0 of 1), a test target for the iOS app | 1.5 weeks | A green PR check, iOS job included |
| Mobile | 🟡 sign-in, sync, history, detail, HR, profile and units, zones, map, IT/EN, account deletion, health write | the experience rows above; store declarations for the health permissions; adapter unit tests (Android has 3 test files, none for the service, the recovery, Bluetooth or permissions); the profile (units, maximum heart rate) is hidden without an account, which the "no account" row has to fix | with the experience rows | Matrix rows S32–S41 |
| Web | 🟡 list, detail, map, analysis, zones, records, GPX / FIT / archive import, account, profile, IT/EN | tests for the list, detail, account and auth pages; private zones; sign-up page (needs the IdP on the cloud) | 1.5 weeks | Page checks + web tests |
| Backend | 🟡 ingest, analysis, records, consents, quotas, export, deletion, FIT and archive import, profile | privacy zones (`V13`; the heart-rate zones already exist); `devices` table (`V14`) only with Synapse L1 | 1 week | Backend tests (Testcontainers) |
| Sensors | 🟡 standard HR on both platforms; the Synapse frame parser and pinned contract are in place and not wired | S29 (band as a strap); adapter unit tests; L1 adapters wait for the prototype and the handoff | 1.5 weeks after the prototype | S15, S16, S29–S31 |
| Managed cloud | 🟡 Compose stack with TLS, runbooks, `backup.sh` / `restore.sh` with a drill and a signed-in round trip in CI | EU VM 🔒, off-site target 🔒, a restore drill on the real host, uptime checks and alerts, SMTP 🔒, open sign-up in the IdP, map tiles; controller liability reviewed before opening | 1.5 weeks | Restore drill log; alert fired in a test |
| Maps | 🟡 MapLibre on web and phones | tile source for the cloud | in [ADR-0027](../adr/0027-managed-cloud-baseline.md) | Tiles load on the cloud instance |
| Legal | 🟡 [ADR-0013](../adr/0013-health-data-special-category.md), data classification, threat model, [DPIA draft](../privacy/dpia.md) (technical part) | the controller's decisions in the DPIA (the owner, as a natural person, until a company exists), the texts of the privacy notice and the terms, hosting DPA, store labels, a trademark check on the chosen name 🔒 | outside the code: 3–5 calendar weeks | Texts published, store forms accepted |
| Support and ops | 🟡 [incident runbook](../runbooks/incident.md) | support mailbox, status page | 2 days | Runbook in `docs/runbooks/` |
| Synapse (separate repository) | 🟡 the handoff of [ADR-0026](../adr/0026-averyn-synapse-link.md) is not done | RR in `0x2A37`, the layout and sentinels in the contract JSON, vectors from the SDK encoder, the interface document rewritten, the Woodpecker typo | in the Synapse repository, [#72](https://github.com/AlessioBrillo/averyn/issues/72) | PRs merged there |

## Increments

Each increment lists its files and the matrix rows it needs. The order is in the [roadmap](roadmap.md#work-order).

| Inc | Scope | Main files | Rows |
|---|---|---|---|
| 1 | Campaign and builds | `docs/deployment/tester-builds.md`, `docs/testing/device-matrix.md`, the fixes it finds | S1–S28 |
| 2 | Design system and mockups | `docs/design/TDD-0015-consumer-ux.md`, mockups | approved by the owner |
| 3 | Experience on Android | `apps/android/.../MainActivity.kt` split into screens, `ui/theme/`, `History.kt`, `TrackMapView.kt` | S47, S48 |
| 4 | Experience on iOS | `ContentView.swift`, `RecorderViewModel.swift`, `TrackMapView.swift`, a test target in `project.yml` | S47, S48 |
| 5 | Progress | `shared/client` (totals, calendar), both apps, web | S45 |
| 6 | Privacy zones | `backend/.../V13__privacy_zones.sql`, `shared/api`, `shared/analysis`, both apps, web | S42 |
| 7 | Share card | both apps | S43 |
| 8 | Voice cues | `shared/tracking` (per-km events), `TrackingService.kt`, `LocationTracker.swift` | S44 |
| 9 | Onboarding and accessibility | both apps | S46, S48 |
| 10 | Cloud, legal, stores | `infrastructure/compose/`, `docs/privacy/dpia.md`, store listings | beta feedback |
| 11 | Synapse L1 beta | `shared/sensors`, both native adapters, `V14__devices.sql` | S30, S31 |

Already built and waiting for the campaign: the profile, zones and phone map (S32–S34), FIT and archive import (S35, S36), account deletion (S37), HealthKit / Health Connect write (S38, S39), IT/EN (S40, S41).

## Gates only the owner can open

1. The public name, before the store listing ([ADR-0008](../adr/0008-project-codename.md)).
2. Store accounts as a natural person (Apple Developer, Play Console) and the signing secrets.
3. The hosting provider and region ([ADR-0027](../adr/0027-managed-cloud-baseline.md)), SMTP, the off-site backup target.
4. The approval of the design (mockups) before the screens are built.
5. A decision on the controller's liability before the cloud opens to the public (a company, or an accepted risk).
6. The Synapse prototype, for L1.

Already open: the devices (≥ 2 Android, ≥ 2 iPhone, a BLE strap) and a Mac.
