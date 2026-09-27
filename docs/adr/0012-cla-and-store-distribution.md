# ADR-0012: Contributor License Agreement, resolving the App Store distribution question

- **Status:** accepted
- **Date:** 2026-09-27
- **Deciders:** @AlessioBrillo

## Context and problem

[ADR-0002](0002-license-agplv3.md) accepted AGPL-3.0-or-later but left the CLA-vs-DCO choice pending, and until it's settled, external code contributions aren't accepted at all. Two things now force the decision: contributors are expected to join within the next few months ([`docs/strategy/00-visione-strategica-completa.md`](../strategy/00-visione-strategica-completa.md) names a small team), and the mobile apps need to reach the Apple App Store, which requires the app distributor to hold rights broad enough to grant Apple's standard distribution terms — a guarantee a plain AGPL codebase with many independent copyright holders (DCO-only, no CLA) cannot give without every contributor's separate sign-off each time. This is one decision, not two: whoever can grant App Store distribution rights is whoever needs to hold the CLA.

## Options considered

1. **DCO only** (`Signed-off-by`, no separate agreement) — lowest friction for contributors, standard in kernel-style projects. Doesn't grant the project the additional rights (e.g. relicensing for App Store terms, future dual-licensing) needed here.
2. **CLA via a bot (cla-assistant or similar)**, held by an entity that can grant those additional rights. Adds a small one-time step for contributors (sign once, enforced automatically on each PR); unblocks store distribution and keeps dual-licensing open.
3. **No external contributions until a legal entity exists** — the current ADR-0002 status quo. Blocks the "1–2 people soon" plan.

## Decision

**Option 2.** A CLA (Apache-style Individual Contributor License Agreement, adapted for AGPL — see `CLA.md`, flagged as needing a real legal review before it's relied on) is required for every external contribution, enforced by `cla-assistant` on GitHub. The CLA is held by **Alessio Brillo personally** for now, with an explicit assignment clause so it transfers cleanly to a future legal entity (company) without needing contributors to re-sign. This is what lets Averyn:

- Distribute the AGPL codebase through the Apple App Store (the CLA holder can grant the additional permission App Store terms require, e.g. via a short store-distribution addendum to the AGPL license text, without needing every individual contributor's separate sign-off).
- Offer dual-licensing later (e.g. a commercial license for organizations that can't use AGPL) if that ever becomes relevant to sustainability.

This resolves ADR-0002's pending item: **CLA, not DCO.** External contributions can open once `cla-assistant` is configured on the repo (a GitHub App install — the user does this, not an agent) and `CLA.md` exists.

## Consequences

- Good: unblocks onboarding contributors and App Store distribution at the same time, with one signature per contributor instead of a relicensing negotiation later.
- Cost: CLA bots add minor friction for first-time contributors (one-time signature); some potential contributors dislike CLAs on principle.
- Cost: `CLA.md` as drafted is a template, not legal advice — it must go through actual legal review before the project relies on it for a commercial or store-distribution claim.
- Follow-up: update [`CONTRIBUTING.md`](../../CONTRIBUTING.md) to point at the CLA instead of "not accepted"; enable `cla-assistant` on the GitHub repo (manual step for the repo owner).

## Revisit when

A legal entity is formed (reassign the CLA, don't re-sign contributors) or legal review of `CLA.md` changes its terms materially.
