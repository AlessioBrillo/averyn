# TDD-0016: Privacy zones

- **Status:** draft (the server, the shared code and the phone's store are implemented with tests; the screens of both phones and the share card wait for the approved design of [TDD-0015](TDD-0015-consumer-ux.md), and the matrix row S42 is not run)
- **Version:** 0.1
- **Author:** @AlessioBrillo
- **Date:** 2026-11-05
- **Related:** [ADR-0028](../adr/0028-consumer-launch-target.md), [data classification](../privacy/data-classification.md), [TDD-0012](TDD-0012-profile-zones-phone-map.md) (the profile this follows), [device matrix](../testing/device-matrix.md) rows S42 and S43, issue #126

## 1. Summary

A privacy zone is a circle around a place the user does not want to show: a home, a workplace. The part of a track inside a zone is hidden on every map, card and web page; the recorded file and the private export keep every sample. The zones work with no account (on the phone) and, with a session, follow the account to the server and the web.

## 2. Goals and non-goals

Goals: hide the start and the end of a run from whoever sees a map or a card, including the owner's own screenshots; changing a zone changes the display of old activities; work offline and without an account; no new place a coordinate can leak (logs, errors, analytics).

Non-goals: changing the recorded data, the distance, the time or any metric (zones are a view); hiding the zone itself (its circle is not drawn); other users' views and sharing by link (no such feature exists yet: the rule for it is in §5.4); automatic zones around the start (the user places them).

## 3. Functional requirements

| Id | Requirement |
|---|---|
| Z1 | A zone is `latitude`, `longitude`, `radiusM` (50 to 2000 m) and an optional `label` (up to 40 characters, never drawn). At most 10 zones. The rules are `PrivacyZones.problem()` in `shared/api`, applied on the phone before saving and on the server, which answers `422` and stores nothing. |
| Z2 | A point of a track inside any zone (great-circle distance to its centre at most the radius) is hidden. A hidden stretch breaks the line, so a map never draws a segment across a zone; a piece left with one point is dropped; a track with nothing left has no geometry. |
| Z3 | `GET /v1/activities/{id}/track` applies the zones of the owner. The summary, the splits, the series and every number are the real ones. |
| Z4 | The raw file, the GPX export and the account export keep every sample. The zones themselves are part of the account export (`privacy-zones.json`) and are deleted with the account. |
| Z5 | The phone keeps its zones in its own file (`zones.json`, next to the profile): they work with no account, and a change made offline goes up with the next sync. The track the phone shows from the copy of the server has the phone's zones applied again, so a zone made after the copy was taken still covers it. |
| Z6 | `GET` and `PUT /v1/privacy-zones`, authenticated, owner-only, under the per-user rate limit; the `PUT` replaces the whole list. |

## 4. Non-functional requirements

- **Privacy:** the centre of a zone is usually a home: Restricted ([data classification](../privacy/data-classification.md)). Never in a log, an error body, a metric label or an analytics event; an invalid body is answered with the rule that failed, not with the value. The file on the phone is not in the copy of the server (it is not thrown away with the session) and is the user's own, like the profile.
- **Safety of the cut:** if the server cannot read a track to cut it, it answers an error: it never falls back to the uncut track.
- **Cost:** a handful of distance computations per point of a track, on the read of one activity; nothing on the write path.

## 5. Design

### 5.1 Data

`V13__privacy_zones.sql`: one row per zone (`owner_id`, `position`, `latitude`, `longitude`, `radius_m`, `label`), the rules repeated as `CHECK` constraints, replaced as a whole in one transaction (`PrivacyZoneStore`). Added to the deletion list and the export of `UserData`.

### 5.2 The cut

`Track.hidingZones(zones)` in `shared/analysis`, on the DTO of the contract, with `haversineMeters` of `shared/metrics`. It is pure and returns the same instance when there are no zones. The server calls it through `hideZonesIn(featureGeoJson, zones)` (read, cut, write; the string untouched with no zone); the phone calls it in `HistoryRepository` on the track of a detail, from the copy or from the server.

### 5.3 The phone

`LocalZonesStore` (the same shape as `LocalProfileStore`): `StoredZones(zones, unsynced)`. `HistoryRepository.savePrivacyZones` saves locally first and sends when there is a token; `syncPrivacyZones` sends what is unsynced and otherwise takes the server's list; an edit made while a call is on its way is not overwritten by the answer. The same limit as the profile applies: "the last change made wins" is the unsynced flag and not a timestamp.

### 5.4 What a future share-by-link must do

Whoever else can see a track (a public activity, a link) sees it through the same cut with the zones of the owner, and a zone is not removable by the viewer. The share card of #127 is made on the phone with the phone's zones. Neither exists yet.

### 5.5 Known limits

- A zone hides the points inside it, not the fact that a run starts at the edge of one: the first visible point is just outside the radius. A larger radius hides more.
- A track copy cached by a phone before the zone existed is cut again when shown, but the bytes in the cache are the uncut-by-this-zone ones until the copy is refreshed; the cache is excluded from the backup and thrown away with the session.
- The web page shows what the server sends (cut); it does not keep its own copy.

## 6. Test strategy

- `shared/analysis` (`PrivacyZoneCutTest`, `commonTest`, so iOS runs it): start hidden, a stretch in the middle breaks the line, one-point pieces go, a fully hidden track has no geometry, every line on its own, no zones returns the same instance, the input is not changed.
- `shared/api` (`PrivacyZonesTest`): the rules of Z1.
- Backend (Testcontainers): round trip and replace, private to the owner, `422` and nothing stored for every rule, an error never repeats a coordinate, deletion, export, the track endpoint cuts and shows the whole track again when the zones go while the stored bytes never change.
- Client (`HistoryRepositoryTest`): a zone made offline covers a copied track, invalid zones are refused locally, an unsynced list goes up, the server's list is taken after, an edit in flight is kept.
- Matrix S42 (a device run) is the proof for the whole chain, and is open.

## 7. Rollout and migration

The migration is additive. No screen sets a zone yet: the API and the phone's store are used by the settings screen and the share card (#123, #124, #127) when the owner approves the design. Until then the feature is reachable by the API only.

## 8. Risks and mitigations

| Risk | Mitigation |
|---|---|
| A coordinate of a zone ends up in a log or an error | No log line takes a body or a zone; the tests assert that an error does not repeat one; code review checks new log lines |
| The server shows the uncut track after an error | The cut raises instead of falling back |
| The phone and the server disagree on the list | Local-first with an unsynced flag, the server answer never overwrites a newer local edit; the last change wins (limit documented) |
| A zone is too small to hide anything | Radius from 50 m; the screens will suggest 200 m |

## 9. Open questions

| Question | Owner | Becomes |
|---|---|---|
| How zones are drawn and edited (a map pin and a slider, or a list), and a default suggestion of a zone around the first start | owner | the screens of [TDD-0015](TDD-0015-consumer-ux.md) |
| Whether a future public activity may use a smaller zone than its owner's private view | owner | a section of the sharing design |
