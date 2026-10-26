# MVP-L: launch checklist

Status: living document. The goal and the exit criteria are in the [roadmap](roadmap.md#mvp-l-launch); this page is the checklist behind them. A row is closed when its evidence exists, not when its code merges. Estimates are for one developer.

Legend: ✅ in place · 🟡 partly · ⬜ not started · 🔒 needs something only the owner can do.

## Gap analysis

| Area | State | Missing | Estimate | Evidence that closes it |
|---|---|---|---|---|
| Device validation | 🟡 protocol ready, matrix empty | tester builds; 4 devices × S1–S28; the fixes the campaign finds; `distance-v2` from the data | 3 weeks, in parallel | Filled rows in the [device matrix](../testing/device-matrix.md) |
| Distribution | 🟡 Android signing wired, never run; iOS manual | secrets 🔒, Play internal, TestFlight, signing team in `project.yml` | 2–3 days + store waits | A tester build installed from each store |
| CI | 🟡 GitHub green except iOS; Woodpecker did not run on PRs | iOS build fixed, all shared modules tested on iOS, end-to-end job with the real IdP | 2 days | A green PR check, iOS job included |
| Mobile | 🟡 sign-in, sync, history, detail, HR, profile and units, zones, map (rows S32–S34 open) | in-app account deletion, onboarding, IT/EN, HealthKit / Health Connect write, accessibility basics | 4 weeks | Matrix rows for each; store review passed |
| Web | 🟡 list, detail, map, analysis, zones, records, GPX / FIT / archive import, account, profile | legal pages, IT/EN, sign-up page | 1.5 weeks | Page checks + web tests |
| Backend | 🟡 ingest, analysis, records, consents, quotas, export, deletion | FIT decoder and archive import, user profile, `devices` table, Synapse sidecar | 2.5 weeks | Backend tests (Testcontainers) |
| Sensors | 🟡 standard HR on both platforms | adapter unit tests; Synapse feature frame (beta); `rr-quality-v1` is after launch | 1.5 weeks | Rows S15, S16, S29–S31 |
| Managed cloud | 🟡 Compose stack with TLS, runbooks | EU VM, off-site backup and a restore drill, uptime checks and alerts, SMTP, open sign-up in the IdP, map tiles | 1.5 weeks | Restore drill log; alert fired in a test |
| Maps | 🟡 MapLibre on the web | tile source for the cloud | in [ADR-0027](../adr/0027-managed-cloud-baseline.md) | Tiles load on the cloud instance |
| Legal | 🟡 [ADR-0013](../adr/0013-health-data-special-category.md), data classification, threat model | company 🔒, DPIA, privacy policy, terms, hosting DPA, store labels, trademark check on the name 🔒 | outside the code: 4–6 calendar weeks | Signed documents, store forms accepted |
| Support and ops | ⬜ | support mailbox, status page, incident runbook | 2 days | Runbook in `docs/runbooks/` |

## Increments

Each increment lists its files and the matrix rows it needs. The schedule is in the [roadmap](roadmap.md#work-order).

| Inc | Scope | Main files | Rows |
|---|---|---|---|
| 0 | Realignment | `apps/ios/Averyn/AuthManager.swift`, `AppModel.swift`, `.github/workflows/ci.yml`, `.woodpecker/ci.yml`, these docs | S16 on iOS |
| 1 | Tester builds | `apps/ios/project.yml`, `.github/workflows/release.yml`, `docs/deployment/tester-builds.md` | S1–S16 |
| 2 ✅ | Profile and phone map (done in code) | `backend/.../V12__user_profile.sql`, `shared/api`, `shared/metrics` (`hr-zones-v1`), `apps/android`, `apps/ios` | new rows |
| 3 ✅ | FIT and archive import (done in code) | `backend/.../imports/`, `apps/web` import page | new rows |
| 4 | Account, onboarding, i18n, legal pages | app string resources, `apps/web` | new rows |
| 5 | HealthKit / Health Connect (write) | `HealthKitExporter.swift`, `HealthConnectExporter.kt` | new rows |
| 6 | Synapse feature frame (beta) | `shared/sensors`, both native adapters, `V13__devices.sql` | S29–S31 |
| 7 | Managed cloud and closed beta | `infrastructure/compose/`, `docs/runbooks/incident.md` | beta feedback |
| 8 | Compliance and stores | `docs/privacy/dpia.md`, store listings | — |

## Gates only the owner can open

1. The company exists (store developer accounts and the DPIA depend on it).
2. The public name, by week 10 ([ADR-0008](../adr/0008-project-codename.md)).
3. Apple Developer and Play Console accounts, signing secrets.
4. The hosting provider and region ([ADR-0027](../adr/0027-managed-cloud-baseline.md)).
