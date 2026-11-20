# ADR-0031: Social and community come after the launch, inside one instance, private by default

- **Status:** superseded by [ADR-0036](0036-social-in-the-launch.md)
- **Date:** 2026-11-15
- **Deciders:** @AlessioBrillo

## Context and problem

[ADR-0028](0028-consumer-launch-target.md) set the launch around three moments and left social, segments and community to MVP-4, after offline maps (MVP-3). The owner's strategic intent, restated on 2026-11-15: people stay on a sports platform because it is also a community (profiles, groups, shared effort), so Averyn needs the same reach in a free, private and open way, and later more sections for every kind of athlete. A larger user base is also what lets the Synapse band find buyers ([ADR-0032](0032-revenue-model.md) says how).

The tension: [vision.md](../product/vision.md) says private by default, health data is a special class ([ADR-0013](0013-health-data-special-category.md)), and the campaign has 0 of 50 rows green. A social graph multiplies the places where a home address can leak (feed, public pages, segments, leaderboards).

## Options considered

1. **Social inside the launch.** Fastest to a community; delays the launch again and builds on tracking nobody has proved.
2. **Social right after the launch, before maps, in thin vertical slices** (chosen).
3. **Keep the order of the roadmap** (maps first). Safe, but maps do not grow a community.

## Decision

**Option 2.** MVP-L is unchanged. After it, **MVP-4 moves before MVP-3**. A slice starts only when the campaign rows that cover what it touches are green; no code before then (the "don't scaffold ahead" rule holds). The slices, in order: profile and follow with approval; visibility enforced on every read; feed; likes, comments and notifications; block, mute, report and moderation tools (before groups: the DSA notice-and-action duty applies to a hosted community); groups and clubs; segments and leaderboards; challenges. New sections follow, one at a time, each with its own ADR or design doc: gym and strength, team sports, coach-athlete, finding training partners.

**Rules that do not move:**

- **Private by default.** A new activity is `private`; a profile is visible; a follow needs the approval of the followed. Visibility is checked **on the server on every read**, and the privacy zones ([TDD-0016](../design/TDD-0016-privacy-zones.md)) are applied to every non-owner surface: feed, page, card, map, segment match.
- **Health data is never social by default.** Heart rate, RR, HRV and stress are not in the feed, in a card or in a leaderboard without a separate consent per scope ([ADR-0013](0013-health-data-special-category.md), [ADR-0021](0021-consent-health-data.md)).
- **Segments are automatic for public activities,** with three limits: an effort that touches a private zone is never matched; the app says in plain words, at the moment an activity becomes public, that it enters the leaderboards; the user can leave per activity.
- **Notifications** go through the platform push services with an empty payload; the app fetches the content from the server.
- **The community is one instance.** See [ADR-0033](0033-no-federation.md).
- **No ranking by paying, no feed built from profiling.** The feed is chronological from the people followed; a ranking, if ever added, is explained and can be turned off.

## Consequences

- Good: the community is built on tracking that has been proved, and every social surface reuses one visibility check and one zone cut.
- Cost: a hosted community brings duties a launch without it does not: moderation, reports, terms, minors. They are a gate of the groups slice, and one developer carries them; the block and report slice comes first for that reason.
- Cost: the cloud holds other people's content: the quota and the DPIA have to cover it before the first slice opens.
- Follow-ups: [roadmap](../product/roadmap.md) and [vision](../product/vision.md) updated; [TDD-0018](../design/TDD-0018-social-graph.md) (draft); DPIA and [data classification](../privacy/data-classification.md) reviewed for the social data.

## Revisit when

The campaign finds that the tracking needs a redesign (social waits), or moderation turns out to cost more than one person can carry (then groups wait, or the slice is cut back to follow and feed).

*Editorial note, 2026-11-20: the timing (after the launch) and the slice order are superseded by [ADR-0036](0036-social-in-the-launch.md); the rules above stay in force.*
