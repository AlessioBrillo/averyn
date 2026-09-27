# Business model

Status: v0.1 — draft, corrects the economics in [`docs/strategy/00-visione-strategica-completa.md`](../strategy/00-visione-strategica-completa.md) per [`docs/strategy/review-2026-09-27.md`](../strategy/review-2026-09-27.md) #22. Figures below are **assumptions to validate**, not commitments — marked where they need real data (supplier quotes, actual VAT/accounting advice, actual cloud usage).

## Principle

Averyn is free and fully functional without any hardware purchase. Nothing in [vision.md](vision.md)'s "never behind a paywall" list changes. Hardware (Synapse or any future device) is a **revenue source that funds shared infrastructure**, never a requirement to use core features.

## Who pays for what

| User type | What they get free | What funds their cost |
|---|---|---|
| GPS-only (the majority, coming from other tracking apps) | Tracking, sync, analysis, export, self-host option, base storage/routing/maps quota — everything in [vision.md](vision.md)'s never-paywalled list | Fair-use quotas on the few genuinely expensive resources (offline map packages, routing calls, photo storage above a generous baseline); donations; club/team plans (optional, recurring, not required for any core feature) |
| Synapse hardware owner | Everything above, plus HRV/stress/sleep-adjacent features via the [Synapse integration](../integrations/synapse.md) | Hardware margin, see below |

Fair-use quotas are a **cost-shape decision, not a feature lock**: past the free quota, a self-hoster pays nothing extra (it's their own infrastructure), and a managed-cloud user either accepts a soft cap (e.g. lower-resolution offline maps) or opts into a paid club/team plan. This needs an actual number once MVP-3 (maps/routing) has real usage data — tracked as an open decision below, not invented here.

## Hardware economics (corrected)

The original figures (BOM ~€120, price €249, margin €129 ≈ 52%) miscounted net revenue. Redone, with **all figures marked `[assumption]` until real supplier/accounting numbers replace them**:

| Line | Value | Note |
|---|---|---|
| BOM `[assumption, from strategy input]` | ~€120 | Needs an actual supplier quote before it's load-bearing |
| Retail price (VAT-included, IT) | €249 | |
| VAT (22%, IT) | ~€45 | `price / 1.22` → net ≈ €204 |
| Net revenue | ~€204 | |
| Gross margin before overhead | ~€84 (≈34% of net, not 52% of gross) | BOM subtracted from **net**, not gross, revenue |
| Not yet subtracted | packaging, shipping, payment processing fees, returns/warranty reserve, one-off costs (certification, tooling, PCB NRE) | Each is `[assumption: unknown]` — these commonly run in the low tens of thousands of euros one-off, plus a few euros per unit ongoing |

**"Margin funds N years of cloud" only holds after those deductions and only for the Synapse-owning subset of users** — it does not fund GPS-only users' cloud cost, which is why fair-use quotas + donations/club plans (not hardware margin) cover that group, per your decision.

## Regulatory constraints on the hardware business

Flagged here so they aren't quietly assumed solved — actual legal review is required before any sale, same caveat as the license decision in ADR-0002:

- **Wellness framing only.** Marketing "readiness"/"stress" as diagnostic risks EU MDR 2017/745 (medical device) scope; see [ADR-0013](../adr/0013-health-data-special-category.md).
- **RED, not just CE/FCC/RoHS.** A radio-equipped consumer device sold in the EU needs Radio Equipment Directive conformity assessment, which the strategy input didn't mention.
- **DPIA before managed processing of health-adjacent data** ([ADR-0013](../adr/0013-health-data-special-category.md)) — a process gate, not a cost line, but it blocks launch if skipped.

## Open decisions

- Fair-use quota thresholds (maps, routing, storage) — set from real usage data at MVP-3, not guessed now.
- Club/team plan pricing — same, deferred to when there's a plan worth pricing.
- Legal entity formation timing (affects VAT treatment, the CLA per [ADR-0012](../adr/0012-cla-and-store-distribution.md), and who is the GDPR controller for the managed cloud).
- Real BOM, certification and one-off costs — replace the `[assumption]` figures above once known.
