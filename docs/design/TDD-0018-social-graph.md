# TDD-0018: Social graph: profiles, follow, block, visibility (slice S1)

- **Status:** draft (becomes `accepted` in the PR that ships the backend; slice order and gates in [ADR-0036](../adr/0036-social-in-the-launch.md))
- **Version:** 0.2
- **Author:** @AlessioBrillo
- **Date:** 2026-11-20
- **Related:** [ADR-0036](../adr/0036-social-in-the-launch.md), [ADR-0033](../adr/0033-no-federation.md), [ADR-0013](../adr/0013-health-data-special-category.md), [data classification](../privacy/data-classification.md), [TDD-0016](TDD-0016-privacy-zones.md), [moderation runbook](../runbooks/moderation.md)

## 1. Summary

Public profile with a handle, follow with approval, block, report, and visibility enforced on every read, in the Ktor monolith (package `social`), on PostgreSQL. It is the base every later slice (feed, interactions, groups, segments, challenges) stands on and it holds the rule that matters: **one visibility check and one zone cut for every non-owner surface**. The feed is the next slice (S2).

## 2. Goals and non-goals

Goals: a profile (display name, `@handle`, sport, bio, avatar; no real name required); follow requests the followed approves; per-activity visibility (`private` default, `followers`, `public`, `link`) and a per-user default for new activities; block; report; an in-app count of pending requests; web and Android screens.

Non-goals: feed, likes, comments, push, groups, segments, search (a person is found by exact handle), ranking, any use of health data, iOS screens (draft PR when a Mac compiles them), an admin console. The additions of [ADR-0034](../adr/0034-social-scope-and-device-openness.md) each get their own TDD.

## 3. Functional requirements

| ID | Requirement |
|---|---|
| F1 | A new activity gets the owner's default visibility, which starts `private`. Only the owner changes it, per activity, and a re-upload never changes it. |
| F2 | A follow is a request; it is active only when the followed accepts. A profile marked private shows only handle and display name to non-followers. |
| F3 | Every read of an activity by a non-owner goes through one function, `ActivityAccess`, that decides visible / not visible from (viewer, owner, visibility, follow state, block state, profile privacy) and applies the owner's privacy zones before returning coordinates. Not visible and not existing answer the same `404`. |
| F4 | A non-owner sees the summary (sport, date, distance, time, pace, elevation) and the zone-cut track. Never heart rate, RR, HRV, stress or analysis series of those (F5). |
| F5 | Health-class data is never in a non-owner view without a separate consent per scope ([ADR-0013](../adr/0013-health-data-special-category.md)). Not part of S1. |
| F6 | Deleting an account or an activity removes it from every other person's view at once. Follows and blocks of the account go with it. |
| F7 | A block removes the follow in both directions, hides both people from each other, and refuses a new follow either way. |
| F8 | A handle is 3-30 chars of `a-z 0-9 _ .`, unique ignoring case, not reserved (`admin`, `averyn`, `support`, `me`, `help`, `root`, `system`, `moderator`...), changed at most once every 30 days. Setting one needs `ageConfirmed = true` (16 or older). |
| F9 | An avatar is decoded and re-encoded as a 256 px JPEG by the server; the original is discarded, so no metadata survives. Max 5 MB in; counts in the quota. |
| F10 | `link` visibility creates an unguessable token (128-bit random); changing to another visibility clears it. The shared page is served without a login, rate limited per IP. |
| F11 | Every non-owner response, and every page for `link` and `public`, carries `X-Robots-Tag: noindex` and the web pages a robots meta. |
| F12 | A report names a user or an activity, one reason from a closed list and an optional note (≤ 500). One open report per (reporter, target). |
| F13 | After the account is deleted, the report stays 6 months without the reporter, then the runbook removes it. |

## 4. Non-functional requirements

Privacy first: an IDOR test for every route with a viewer who is the owner, a stranger, a pending follower, a follower, a blocked user and no one (unauthenticated). A test enumerates the routes under `/v1/users` and `/v1/shared` and fails if one is not registered through `ActivityAccess`. Reads bounded by a cursor and an index on `(owner_id, started_at)`. Writes rate limited per user (`PER_USER`), follows and reports with a lower limit. No new infrastructure and no new dependency (`javax.imageio` for the avatar).

## 5. Design

**Tables (`V15__social.sql`; the `devices` table moves to `V16`):**

- `user_profiles` gains `handle`, `handle_changed_at`, `display_name`, `bio` (≤ 160), `sport`, `avatar_key`, `profile_private`, `default_visibility` (same CHECK as V3), `age_confirmed_at`; unique index on `lower(handle)`.
- `follows(follower_id, followed_id, state, created_at)`, state `pending | active`, PK `(follower_id, followed_id)`, index on `followed_id`.
- `blocks(blocker_id, blocked_id, created_at)`.
- `reports(id, reporter_id NULL, target_user_id, target_activity_id NULL, reason, note, created_at, resolved_at)`.
- `activities.share_token` (unique, nullable).

**Access.** `canView(viewer, owner, visibility, followState, blocked, profilePrivate)` is a pure function with a table test; the loader reads the activity, the follow and the block in one query. The track is `trackGeoJson` + `hideZonesIn(..., zoneStore.get(owner))` ([TDD-0016](TDD-0016-privacy-zones.md)) and nothing returns coordinates another way.

**Endpoints (all authenticated, `PER_USER`, except `/v1/shared`):**

| Route | Purpose |
|---|---|
| `GET/PUT /v1/me/public-profile` | read / write own profile (handle, name, bio, sport, private, default visibility, ageConfirmed) |
| `PUT/DELETE /v1/me/avatar`, `GET /v1/users/{id}/avatar` | avatar |
| `GET /v1/users/{id}`, `GET /v1/users/by-handle/{handle}` | profile card (+ relation: none, pending, following, blocked) |
| `GET /v1/users/{id}/activities`, `.../{aid}`, `.../{aid}/track` | visible activities of a person (cursor) |
| `PUT /v1/activities/{id}/visibility` | owner; returns the token for `link` |
| `POST/DELETE /v1/users/{id}/follow` | request / cancel / unfollow |
| `GET /v1/me/follow-requests`, `POST .../{id}/accept`, `.../decline`, `DELETE /v1/me/followers/{id}` | the followed's side; the list length is the badge |
| `GET /v1/users/{id}/followers`, `.../following` | lists, visibility-checked |
| `PUT/DELETE /v1/me/blocks/{id}`, `GET /v1/me/blocks` | block |
| `POST /v1/reports` | report |
| `GET /v1/shared/{token}`, `.../track` | unauthenticated, per-IP limit |

Users are opaque ids in every response ([ADR-0033](../adr/0033-no-federation.md)); a handle only resolves to an id.

**Deletion and export.** `UserData.deleteAccount` removes follows and blocks on both sides and the avatar object, and sets `reports.reporter_id = NULL`. The export adds the public profile, the follows, the blocks and the reports the user made.

## 6. Test strategy

Backend integration tests (Testcontainers): the IDOR matrix over routes × viewers × visibilities × profile privacy; a track that starts inside a zone is cut for every non-owner surface; no health field in any non-owner body; deletion removes the person from every other view (F6); handle rules and the 30-day change; avatar with GPS EXIF comes out without it; the route guard test. Shared: contract tests of the DTOs. Web: vitest for the pure modules. Android: unit tests for any logic. Device-matrix rows S51-S56 before `verified`.

## 7. Rollout and migration

No feature flag: the code ships when its tests and its matrix rows are done; the public surfaces open on the managed cloud only after the gates of [ADR-0036](../adr/0036-social-in-the-launch.md). Existing activities are `private` and existing profiles have no handle, so nothing becomes visible by migration.

## 8. Risks and mitigations

| Risk | Mitigation |
|---|---|
| A surface forgets the check and leaks a location | One function (F3); the route guard test fails if a route skips it |
| Free text and images hosted without moderation | Report from the first slice; runbook; avatar re-encoded; the console is designed with S4/S5 |
| Minors | Age gate of 16 on the handle (F8) and in the terms before the first public surface; issue #74 |
| A shared link becomes a map of habits | `noindex` always (F11), zones cut, the token can be cleared by changing visibility |
| Handle squatting or impersonation | Reserved words, 30-day change limit, report reason `impersonation` |

## 9. Open questions

None blocking S1. For S2: whether the feed shows an activity that became `followers` after the follow was accepted (default: yes, it is a read of current visibility).
