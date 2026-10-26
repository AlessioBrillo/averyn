# Data protection impact assessment (DPIA) — managed Averyn service

- **Status:** draft v0.1 — the technical part, written from what the system does today. It is **not** legal advice and it is not final: the items marked **[controller]** need decisions and signatures of the company that runs the service, and the legal basis, the retention periods and the conclusion need review by a data protection professional before the managed service processes anyone's data ([ADR-0013](../adr/0013-health-data-special-category.md): the DPIA is a release gate).
- **Date:** 2026-10-26
- **Scope:** the managed cloud described in [ADR-0027](../adr/0027-managed-cloud-baseline.md) and the apps and web of the launch ([MVP-L](../product/mvp-launch.md)). Self-hosters are their own controllers; they get the same mechanisms.
- **Related:** [data classification](data-classification.md), [threat model](threat-model.md), [ADR-0011](../adr/0011-identity-oidc.md), [ADR-0013](../adr/0013-health-data-special-category.md), [ADR-0017](../adr/0017-data-deletion-and-retention.md), [ADR-0021](../adr/0021-consent-health-data.md), [ADR-0025](../adr/0025-device-side-cache-of-server-activities.md), [ADR-0026](../adr/0026-averyn-synapse-link.md)

## 1. What is processed, why, and where it goes

| Data | Examples | Purpose | Class | Where it lives | Who can read it |
|---|---|---|---|---|---|
| Identity | the OIDC subject and issuer of the sign-in, the email at the identity provider | to know whose data is whose | Confidential | the identity provider; the Averyn `users` table holds the subject and issuer only | the operator (the provider's admin console), the user |
| Location | the samples of a recording: latitude, longitude, altitude, time, accuracy | the product itself: tracking, distance, maps, analysis | Restricted | the phone; the object store (the raw file); PostGIS (the track line) | the user. No other user: there is no sharing feature yet |
| Heart rate | bpm of a strap, per second | heart-rate numbers and zones | Restricted (health-adjacent) | the raw file; the analysis tables | the user |
| Beat-to-beat intervals (RR) | the intervals between beats | a future HRV metric; stored only with consent | **Health (special category)** | a separate sidecar object per activity | the user; the operator's storage layer |
| Settings | the unit system, the maximum heart rate | to show numbers in the user's units and cut heart-rate zones | Restricted | the `user_profiles` table | the user |
| Imported files | a GPX or FIT file, an archive's files | to bring in the user's history | Restricted | the object store, byte-for-byte | the user |
| Derived data | splits, elevation, series, best efforts, records, zones | the analysis | Restricted | PostgreSQL | the user |
| Consent records | which scope, granted when, revoked when | to prove and honour consent | Internal | the `consents` table | the user, the operator |
| Operational data | request counts, error codes, health checks | to run the service | Internal | logs and metrics: ids and counts only, never coordinates, tokens or content | the operator |
| Diagnostics | the report a user exports from the app for a bug or a test run | support | Restricted | the user's device, shared by the user on purpose | whoever the user sends it to |

Not processed: advertising identifiers, contacts, photos, the content of other apps, any third-party analytics SDK (telemetry is off by default and carries no location, [ADR-0009](../adr/0009-observability-baseline.md)). The phone's copy of the server is bounded, excluded from the backup and ends with the session ([ADR-0025](../adr/0025-device-side-cache-of-server-activities.md)); the maximum heart rate never reaches it.

**Recipients and processors [controller]:** the hosting provider (virtual machine, object storage and backups, [ADR-0027](../adr/0027-managed-cloud-baseline.md)), the email provider used by the identity provider for sign-up and verification, the provider of the map tiles if it is not self-hosted. Each one needs a data processing agreement and a place in the register of processing. The identity provider itself runs inside the stack (Zitadel), so it is not a separate recipient. The tile provider sees the area a user looks at: the default of the managed service is self-hosted tiles ([ADR-0027](../adr/0027-managed-cloud-baseline.md)), which removes that recipient.

## 2. Is a DPIA needed, and what makes this one sensitive

Systematic, large-scale processing of location data and health-adjacent data of identifiable people is the kind of processing a DPIA is for. Three things make this service sensitive: a track reveals home, work, routines and absences; heart rate is health-adjacent and the beat-to-beat intervals are health data in the strict sense; and a recording happens in the background, so the user is not looking at the screen when data is collected.

## 3. Necessity and proportionality

| Question | Answer from the design |
|---|---|
| Is each datum needed? | Location and heart rate are the product. RR intervals are not needed by any feature today: they are collected **only** with a specific, revocable consent, and no metric uses them yet. The decision to keep collecting them before `hrv` exists is the controller's **[controller]**: the lowest-risk choice is to not offer the grant at launch |
| Legal basis **[controller, counsel]** | For the account and the activities: performance of the contract, or consent. For RR intervals and any other health-class data: explicit consent per scope (Art. 9(2)(a)), already built as a server-authoritative, fail-closed, revocable grant ([ADR-0021](../adr/0021-consent-health-data.md)) |
| Retention | Until the user deletes an activity or the account; then hard delete, immediately, in the object store and in the database ([ADR-0017](../adr/0017-data-deletion-and-retention.md)). Backups are the operator's: they expire within a documented period and are re-applied on restore **[controller: choose the period]** |
| Data subject rights | Access and portability: the export (a ZIP of the raw files, the originals, the analyses, the profile, the consents). Erasure: delete one activity or the account, on the web and in both apps. Rectification: the profile is editable; recordings are not edited (they are measurements). Restriction and objection: revoking a consent stops the processing it covers at once. Withdrawing consent deletes the data it covered |
| Transparency | A privacy notice that says what this table says **[controller, counsel]**; the in-app texts already say what is deleted and what is not |
| Transfers | None by design when hosted in the EU; any processor outside the EU needs its own review **[controller]** |
| Children | Not addressed. The service is not for children; whether to say so or to verify age is a decision of the controller **[controller]** |

## 4. Risks to the people, and what answers them

| # | Risk to the user | Likelihood × impact | Measures in place | Residual / to decide |
|---|---|---|---|---|
| R1 | Someone obtains a user's tracks and learns their home, work and routine | Medium × High | Private by default; per-object access checks on every read, covered by IDOR tests; no sharing feature at launch; TLS everywhere; tokens never in logs | The operator's own administrators can read the storage: access is limited to the operator and logged by the host **[controller: access policy]** |
| R2 | A data breach of the object store or the database | Low × High | Encryption in transit; the host's encryption at rest **[controller: confirm with the provider]**; secrets generated, not in the repository; per-user rate limits and quotas | An incident runbook ([incident.md](../runbooks/incident.md)); notification duties to the authority and the users are the controller's |
| R3 | Health-class data (RR) is collected without a valid consent | Low × High | The adapter does not subscribe without a grant (fail-closed); the consent is re-read while recording; revocation stops the stream and deletes what was stored, leaving a tombstone | None identified |
| R4 | A deleted user's data stays somewhere | Medium × Medium | Hard delete of the objects and rows, repeatable if it fails half-way; the export and the deletion are tested; the copy on the phone ends with the session | Backups keep deleted data until they expire (documented, ADR-0017) |
| R5 | A user cannot get their data out or cannot get it removed | Low × Medium | Export and deletion on the web and in both apps, no paywall | None |
| R6 | A file the user imports attacks the server | Low × Medium | Parsers without external entities, bounded, no allocation driven by the file; limits on size, points and entries; one import slot per request ([threat model](threat-model.md)) | A real-world fuzzing pass is not done |
| R7 | The map shows a third party where a user is | Medium × Medium | The style and the tiles are served by the instance; the demo style is a development default only | The managed service must set its own style (ADR-0027) |
| R8 | Wellness estimates (zones, stress classes) are read as medical advice | Medium × Medium | Wellness wording only, no diagnostic claim; the zones are cut from a maximum the user typed | The product copy and the store listings need review by counsel **[counsel]** |
| R9 | A third-party sensor sends more than was agreed | Low × High | A vendor adapter never subscribes to a raw stream; the vendor's cloud is at most a stateless job worker, with no user identifier ([ADR-0010](../adr/0010-ecosystem-boundary.md), ADR-0026) | The Synapse feature frame is a beta behind its own consent scopes |
| R10 | Another process on the phone reads the copy | Low × Medium | The copy is in the app's private storage, excluded from the backup, bounded and cleared with the session | A lost, unlocked phone shows what the user opened |

## 5. Conclusion and what is still needed before launch

The design answers the risks above with measures that exist in the code and are tested, except where a **[controller]** or **[counsel]** mark says a decision or a review is needed. The DPIA is **not closed** until:

1. The company exists and is named as the controller, with its contact point for data subjects.
2. The legal basis of each processing, the retention period of the backups and the access policy of the administrators are decided and written here.
3. Data processing agreements exist with the hosting and the email providers, and the register of processing is filled.
4. Counsel has reviewed the privacy notice, the terms and the product wording (R8).
5. A restore drill from an off-site backup has been done and logged ([ADR-0027](../adr/0027-managed-cloud-baseline.md)), and the incident runbook has been read by whoever is on call.
6. The decision on RR intervals at launch (section 3) is taken.

Reviewed by: **[controller / DPO — name, date]**. Next review: before the first release that processes a new kind of data, and at the latest a year after launch.

## Annex — facts for the privacy notice

What the notice has to be able to say truthfully, from the system as it is:

- Who we are and how to reach us **[controller]**.
- What we collect: the table in section 1, in plain words. What we do not: advertising ids, contacts, third-party analytics.
- Why: to record, show and analyse your activities; nothing else. We do not sell data and there is no profiling for advertising.
- Who sees your data: you. Our hosting and email providers process it for us under agreements **[controller]**. There is no sharing with other users in this version.
- How long: until you delete it; deletion is immediate in our storage; backups expire within **[period]**.
- Your rights and how to use them in the app and on the web: export, delete an activity, delete the account, edit your settings, withdraw a consent.
- Health data: beat-to-beat intervals are stored only if you agree, per purpose, and you can withdraw at any time; withdrawing deletes them.
- The map: the map tiles are served by us **[or the named provider]**.
- Where to complain: the supervisory authority **[controller: which one]**.
