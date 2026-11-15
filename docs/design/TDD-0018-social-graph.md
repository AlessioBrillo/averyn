# TDD-0018: Social graph: profiles, follow, visibility, feed

- **Status:** draft (no code; slices start after MVP-L and a green campaign, [ADR-0031](../adr/0031-social-and-community.md))
- **Version:** 0.1
- **Author:** @AlessioBrillo
- **Date:** 2026-11-15
- **Related:** [ADR-0031](../adr/0031-social-and-community.md), [ADR-0033](../adr/0033-no-federation.md), [data classification](../privacy/data-classification.md), [TDD-0016](TDD-0016-privacy-zones.md)

## 1. Summary

Profiles, follow with approval, visibility enforced on every read, and a chronological feed, in the Ktor monolith (package `social`), on PostgreSQL. This is the base the later slices (interactions, groups, segments, challenges) stand on; it holds the rule that matters: **one visibility check and one zone cut for every non-owner surface**.

## 2. Goals and non-goals

Goals: a public profile (display name, sport, optional bio, no required real name); follow requests the followed approves; per-activity visibility (`private` default, `followers`, `public`, `link`); a feed of the followed people's visible activities.

Non-goals (this TDD): likes, comments and push ([next slice](../adr/0031-social-and-community.md)), groups, segments, federation, ranking, any use of health data, and the additions of [ADR-0034](../adr/0034-social-scope-and-device-openness.md) (search, gear and goals, photos, trained together, live tracking, heatmap), each with its own TDD when it starts.

## 3. Functional requirements

| ID | Requirement |
|---|---|
| F1 | A new activity is `private`. Changing visibility is an explicit act on one activity. |
| F2 | A follow is a request; it becomes active only when the followed approves. A private profile hides everything but the name from non-followers. |
| F3 | Every read of an activity by a non-owner goes through one function that decides visible / not visible from (viewer, owner, visibility, follow state, block state), and applies the privacy zones before returning coordinates. |
| F4 | The feed lists the visible activities of the people the viewer follows, newest first, paginated by a cursor. No ranking. |
| F5 | Heart rate, RR, HRV and stress never appear in a non-owner view without a separate consent per scope. |
| F6 | Deleting an account or an activity removes it from every other person's feed and view at once. |

## 4. Non-functional requirements

Privacy first: an IDOR test for every endpoint, with a viewer who is a stranger, a pending follower, a follower and a blocked user. Feed read cost bounded by a cursor and an index on (owner, started_at); `ponytail:` fan-out on read, move to fan-out on write only if the measured p95 requires it. No new infrastructure.

## 5. Design

Tables (migrations from V15): `follows(follower, followed, state, created_at)`, `blocks(blocker, blocked)`, profile columns on the existing profile table. `visibility` stays the text column of V3 with its CHECK; the Kotlin type is an enum introduced by the slice that first reads it (the wire value is lowercase). Users are referred to by opaque ids in every response ([ADR-0033](../adr/0033-no-federation.md)).

## 6. Test strategy

Backend integration tests with Testcontainers for the visibility matrix (F3, F5, F6); fixtures with a track that starts inside a private zone; a device-matrix row per user-facing screen before `verified`.

## 7. Rollout and migration

Behind no flag: the slice ships when its tests, its matrix row and the DPIA review for social data are done. Existing activities are `private`, so nothing becomes visible by migration.

## 8. Risks and mitigations

| Risk | Mitigation |
|---|---|
| A surface forgets the check and leaks a location | One function (F3), a test that lists every route returning an activity and fails if one does not call it |
| Moderation load on one person | Block and report come before groups ([ADR-0031](../adr/0031-social-and-community.md)) |
| Minors | Terms and an age gate decided with the legal texts (issue #74) before the first public slice |

## 9. Open questions

- Is a link-visibility page indexed or `noindex`? (owner: @AlessioBrillo; decided with F1, goes in the slice's PR)
- Retention of reports and blocks after an account deletion. (owner: @AlessioBrillo; ADR-0017 amendment)
