# ADR-0036: Social is part of the launch; the order of the community slices

- **Status:** accepted
- **Date:** 2026-11-20
- **Deciders:** @AlessioBrillo
- **Supersedes:** [ADR-0031](0031-social-and-community.md) (the timing and the slice order; its rules stay)
- **Supersedes in part:** [ADR-0028](0028-consumer-launch-target.md) (the scope of MVP-L), [ADR-0017](0017-data-deletion-and-retention.md) (what stays of reports after a deletion)

## Context and problem

[ADR-0031](0031-social-and-community.md) put social after the launch and after a green campaign. On 2026-11-20 the owner decided that a community is what makes people stay and invite others, so it must be in the launch, not after it: Averyn has to be useful to clubs and groups of friends as much as to people who want metrics. The risk ADR-0031 named has not gone away: a social graph multiplies the places where a home address can leak, the campaign has 0 of 50 rows green, and one developer carries moderation.

## Options considered

1. **Keep ADR-0031.** Safe, but the launch has no reason to be shared.
2. **Social in the launch, in thin slices, each behind the one visibility check** (chosen). The rules of ADR-0031 do not move; only the timing does. What protects the launch is the gate on the *public* surfaces, not a ban on the code.
3. **Social replaces the launch.** Rejected: the three moments (recording, summary, history) are still what people come for.

## Decision

**Option 2.** Social code is allowed now, one slice at a time, and ships inside MVP-L. Two things stay locked:

- A slice is **`verified` only when its [device-matrix](../testing/device-matrix.md) row is green**, like every other feature.
- The **public surfaces open on the managed cloud only after** the social section of the [DPIA](../privacy/dpia.md), the terms with an **age gate of 16** (GDPR art. 8 consent age, the same in the whole Union) and the report process are in place. Until then the code runs on self-hosted and test instances.

**Rules that do not move (from ADR-0031):** private by default (a new activity is `private`, a profile is visible, a follow needs the approval of the followed); visibility checked **on the server on every read** by one function; the privacy zones ([TDD-0016](../design/TDD-0016-privacy-zones.md)) applied to every non-owner surface; heart rate, RR, HRV and stress never social without a separate consent per scope ([ADR-0013](0013-health-data-special-category.md)); notifications through the platform push services with an empty payload; one instance ([ADR-0033](0033-no-federation.md)); a chronological feed, no ranking by paying, no feed built from profiling. No admin console: the operator works with runbooks and the database ([ADR-0035](0035-no-admin-interface-before-moderation.md)); the console is designed with the groups slice.

**Added by this ADR:**

- Pages with visibility `link` or `public` are **never indexed** (`X-Robots-Tag: noindex` and a robots meta): a shared link must not become a map of where a person runs.
- A user can set the **default visibility** of their new activities; it starts at `private`, only the user changes it, and it never touches an existing activity.
- A handle (`@name`) is 3-30 characters of `a-z 0-9 _ .`, unique ignoring case, changeable once every 30 days; the API refers to users by opaque id.
- An avatar is re-encoded by the server (metadata gone) to 256 px and replaces the previous one, so it stays small and is not counted in the quota.
- **After an account is deleted:** follows and blocks go at once; a *report* is kept for **6 months with the reporter removed**, as proof of the notice-and-action process, then deleted by the runbook.

### The slices, in order

| Slice | Content | Gate to start | Gate to open publicly |
|---|---|---|---|
| S1 | Profile and handle, follow with approval, block, visibility on every read, report, in-app badge for requests ([TDD-0018](../design/TDD-0018-social-graph.md)) | this ADR | DPIA section, terms, age gate |
| S2 | Feed (chronological, with title and description) | S1 merged | with S1 |
| S3 | Likes, comments, notifications (push with an empty payload) | S2; comments are content: S1's report covers them | with S1 |
| S4 | Moderation tools: roles, audit log, the console decision | S3 | before S5 |
| S5 | Groups and clubs | S4 (the notice-and-action duty of a hosted community) | with S4 |
| S6 | Photos (EXIF and GPS stripped), title, description | S3 | with S3 |
| S7 | Athlete search (by handle and name, never by location) | S1 | with S1 |
| S8 | Segments and leaderboards (automatic for public activities; an effort touching a private zone is never matched; the app says so when an activity becomes public) | S2 and a green privacy-zone row | its own review |
| S9 | Challenges | S5, S8 | with S8 |
| S10 | Gear and goals, "trained together" | S2 | with S2 |
| S11 | Live safety tracking (expiring, revocable link) | S1; any HR sensor adds live HR and a stalled-signal alert | its own review |
| S12 | Global heatmap (k ≥ 5, zones applied) | S8 | its own review |

New sections (gym and strength, team sports, coach-athlete, finding partners) follow, one at a time, each with its own ADR or design doc ([ADR-0034](0034-social-scope-and-device-openness.md)). Each slice gets its own TDD when it starts; S1 is TDD-0018.

## Consequences

- Good: the launch has a reason to be shared; every later slice reuses one check and one zone cut that exist from the first slice.
- Cost: MVP-L grows by several weeks, and moderation, terms, minors and a social section of the DPIA become launch gates, carried by one developer. The cut line of [mvp-launch.md](../product/mvp-launch.md) says what goes first if the time runs out: S2 onward moves back out of the launch, S1 stays.
- Cost: the quota and the DPIA have to cover other people's content before the first public slice.
- Follow-ups: [roadmap](../product/roadmap.md), [launch checklist](../product/mvp-launch.md), [data classification](../privacy/data-classification.md), [DPIA](../privacy/dpia.md), [device matrix](../testing/device-matrix.md) (rows S51-S56), [moderation runbook](../runbooks/moderation.md), [OpenAPI](../api/openapi.yaml); migration `V15__social.sql` (the `devices` table moves to `V16`).

## Revisit when

The campaign finds that the tracking needs a redesign (then the slices that read activities wait), or moderation costs more than one person can carry (then the launch keeps S1 only and the rest returns to after the launch, as ADR-0031 had it).
