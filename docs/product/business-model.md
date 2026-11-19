# Business model

> **Public draft, not a commitment.** The figures below are illustrative
> assumptions, not supplier quotes or an offer. Detailed unit economics and legal
> advice are not published here and will be reviewed before they are relied on.
> The principles are in [ADR-0032](../adr/0032-revenue-model.md).

Status: v0.2 — draft (2026-10-29: aligned with [ADR-0028](../adr/0028-consumer-launch-target.md)), corrects the economics in [`docs/strategy/00-visione-strategica-completa.md`](../strategy/00-visione-strategica-completa.md) per [`docs/strategy/review-2026-09-27.md`](../strategy/review-2026-09-27.md) #22. Figures below are **assumptions to validate**, not commitments — marked where they need real data (supplier quotes, actual VAT/accounting advice, actual cloud usage).

## Principle

Averyn is free and fully functional without any hardware purchase. Nothing in [vision.md](vision.md)'s "never behind a paywall" list changes. Hardware (Synapse or any future device) is a **revenue source that funds shared infrastructure**, never a requirement to use core features.

## Who pays for what

| User type | What they get free | What funds their cost |
|---|---|---|
| Everyone (GPS-only or Synapse owner) | Tracking, analysis, export, self-host option, the managed sync with a high anti-abuse limit per account — everything in [vision.md](vision.md)'s never-paywalled list | The managed cloud at launch is small (activities and their analysis, no maps hosting, no photos, no routing). Its cost is paid by the margin of the Synapse band and by donations; see below for the limit of that promise |
| Synapse hardware owner | Everything above, plus the band's own data through the [Synapse integration](../integrations/synapse.md) | Hardware margin, see below |

**Since 2026-11-15 ([ADR-0032](../adr/0032-revenue-model.md)):** no feature is limited by plan, the users' data is never used to profile or to aim advertising, and the band is presented openly in the app (sensors page, pairing, first run), the same for everyone. Where this page says "quota", read an **anti-abuse limit**: high, published, and never lowered for a single account.

The anti-abuse limit is a **cost-shape decision, not a feature lock**: a self-hoster pays nothing extra (their own infrastructure), and the managed cloud has a per-account limit on stored raw data (it already exists in the backend). Photos, maps and routing are not part of the launch; their limits are set when they exist, on the same rule ([ADR-0034](../adr/0034-social-scope-and-device-openness.md)).

**The promise has a limit, written down so it is not forgotten.** "The band pays for the free cloud" holds only while the number of cloud users is covered by the margin of the bands sold. Until bands are sold the cloud is paid by the project itself (one small VM, see [ADR-0027](../adr/0027-managed-cloud-baseline.md)). If the free users grow faster than the margin, the order of reaction is: ask for donations, check what the limit costs, and, if that is not enough, a new ADR; there is no paid tier ([ADR-0032](../adr/0032-revenue-model.md), [ADR-0034](../adr/0034-social-scope-and-device-openness.md)). Local use without an account and self-hosting are never affected.

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

**"Margin funds N years of cloud" only holds after those deductions and only for the Synapse-owning subset of users** — it is only a partial answer for users who do not own a band, which is why the anti-abuse limit and the donations stay in the model (see the limit above).

## Regulatory constraints on the hardware business

Flagged here so they aren't quietly assumed solved — actual legal review is required before any sale, same caveat as the license decision in ADR-0002:

- **Wellness framing only.** Marketing "readiness"/"stress" as diagnostic risks EU MDR 2017/745 (medical device) scope; see [ADR-0013](../adr/0013-health-data-special-category.md).
- **RED, not just CE/FCC/RoHS.** A radio-equipped consumer device sold in the EU needs Radio Equipment Directive conformity assessment, which the strategy input didn't mention.
- **DPIA before managed processing of health-adjacent data** ([ADR-0013](../adr/0013-health-data-special-category.md)) — a process gate, not a cost line, but it blocks launch if skipped.

## Open decisions

- Anti-abuse limit thresholds of the managed sync — set from the first months of real usage, not guessed now. Photos, maps and routing: when they exist.
- Legal entity: the apps are published by a natural person at launch ([ADR-0028](../adr/0028-consumer-launch-target.md)). **Selling the band needs a company** (VAT, RED, warranty, product liability) and so does a managed service that grows; the controller of the managed cloud is the same person until then, and that has a liability cost to weigh before the cloud opens.
- Real BOM, certification and one-off costs — replace the `[assumption]` figures above once known.
