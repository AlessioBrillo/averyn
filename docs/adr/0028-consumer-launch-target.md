# ADR-0028: The launch target is a free, privacy-first consumer app, built around three moments and proved on devices first

- **Status:** accepted
- **Date:** 2026-10-29
- **Deciders:** @AlessioBrillo

## Context and problem

On 2026-10-25 the target became "MVP-L: a public launch on a managed cloud" (13 weeks, a company as controller). Since then every piece that needs no account was written: profile, zones, phone map, FIT and archive import, health-platform export, Italian and English, account deletion, backup and restore, the Synapse frame parser. An audit on 2026-10-29 found:

- **The order broke the project's own rule** ("validated on real devices first"). The tester builds and the campaign never started; the device matrix has 0 of 48 scenarios run. The largest risk, the reliability of the recording, is still unproven.
- **The effort went to infrastructure and documents, not to the experience.** The main screen of the Android app is a server-URL field and a column of buttons. For a consumer product that is the biggest gap.
- **The business model had no revenue at launch.** It was funded by quotas on offline maps, routing and photos, none of which exist before MVP-3.
- **Several documents contradicted the code** (launch checklist, CI wording, "implemented" on designs nothing had verified), and the strategy folder named third-party products against the brand rule.

The owner's intent, stated in this session: the first public release must be what a mobile user dreams of: an interesting interface, everything free, privacy first, and no waiting for a company.

## Options considered

1. **Keep MVP-L as written** (managed cloud, SRL, 13 weeks). Fits the infrastructure already built; does nothing about the experience or the unproven tracking.
2. **A private beta first, public launch later.** Safe, but it is not what the owner wants and it delays the feedback that matters (strangers' phones).
3. **Self-host first.** Real, but it is not a consumer product.
4. **A free consumer launch, with the experience and the device proof as the core of the work** (chosen).

## Decision

**Option 4.**

**The product.** Free, with no paywall on anything in [vision.md](../product/vision.md), and privacy visible: the app records and shows everything on the phone without an account; the account only adds the sync and the web. Three moments must be excellent, in this order of the owner's choice: the recording screen (readable, with voice cues), the summary after the activity (with a share card), and the history with progress (weeks, months, records).

**New in the launch scope:** privacy zones (hide the start and the end of a track in every view and in the share card; the raw samples stay untouched, only the display is cut), the share card (an image made on the phone, shared by the user's own tap), voice cues (every kilometre), progress views, a first-run flow and accessibility basics. **Already built and kept:** the profile, zones, phone map, FIT and archive import (people arrive with a history), health-platform export, IT/EN, deletion, backup and restore.

**Frozen until the campaign or a prototype says otherwise:** the Synapse feature frame on the phone (L1: adapters, sidecar, `devices`), `rr-quality-v1`, and the gap policy `distance-v2`.

**Order of work.** (1) The campaign and the fixes it finds; (2) the design system and mockups, approved by the owner; (3) the experience on Android and iOS; (4) progress, privacy zones, share card, voice cues; (5) onboarding and accessibility; (6) cloud, legal texts and store listings. A feature is `verified` only when its device-matrix row is green. No new feature starts while a row of the campaign is red for a reason in the tracking.

**Cut line**, in this order: Synapse L1, voice cues, archive import, heart-rate zones. **Never cut:** the campaign, the three moments, privacy zones, account deletion, restore, the legal texts.

**Money.** The managed sync is free with a per-account quota. It is paid by donations and by the margin of the Synapse band; that is only a partial answer for people without a band, and the order of reaction if free users outgrow the margin is written in the [business model](../product/business-model.md): quota, donations, and only then a paid tier for something outside the never-paywalled list.

**Publishing.** The apps are published to the stores by the owner as a natural person; the company is not a gate for the app. Consequences accepted with this: a new personal Play Console account must run a closed test with at least 12 testers for 14 days before production; the owner is the data controller of the managed cloud until a company exists, which has a liability cost; and selling the band needs a company regardless. The public name is the owner's decision, to be taken before the store listing ([ADR-0008](0008-project-codename.md)).

**No date.** The work is ordered, not dated. The estimate is 16–20 weeks of development for one person plus the external waits (stores, legal texts, the Synapse prototype).

**Averyn and Synapse.** As in [ADR-0026](0026-averyn-synapse-link.md): the band talks BLE to the phone, Averyn is the only system of record, Synapse holds no accounts and builds no consumer cloud. L0 (the band as a strap) is part of the launch; L1 follows when the prototype exists.

## Consequences

- Good: the next weeks go to the two things that decide whether strangers keep the app: it records reliably and it feels good.
- Good: a smaller legal gate at the start; the app can be on a store before a company exists.
- Cost: no dates make it easy to drift; the order and the cut line are the control, and each increment has an exit row in the matrix.
- Cost: the free cloud has no income until bands sell; its size is capped by the quota and the single small VM of [ADR-0027](0027-managed-cloud-baseline.md).
- Cost: publishing as a natural person puts the controller's responsibility on one person (see the business model); this should be reviewed before the cloud opens to the public.
- Cost: the privacy zones, the share card and the voice cues are new code with no design yet; each gets a design doc in the same increment as its code.
- Follow-ups: rewrite the product documents ([vision](../product/vision.md), [roadmap](../product/roadmap.md), [launch checklist](../product/mvp-launch.md)); open one issue per increment; fix the handoff items in the Synapse repository.

## Revisit when

The campaign finds that the tracking itself needs a redesign (then the experience work waits), or the free cloud's cost exceeds what the band margin and the donations can pay (then the business-model reaction order applies).
