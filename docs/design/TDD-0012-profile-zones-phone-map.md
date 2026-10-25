# TDD-0012: Profile, heart-rate zones and the map on the phone

- **Status:** draft
- **Version:** 0.1
- **Author:** @AlessioBrillo
- **Date:** 2026-10-25
- **Related:** [TDD-0009](TDD-0009-activity-analysis.md), [TDD-0011](TDD-0011-mobile-read-path.md), [ADR-0023](../adr/0023-activity-analysis-storage.md), [ADR-0025](../adr/0025-device-side-cache-of-server-activities.md), [ADR-0027](../adr/0027-managed-cloud-baseline.md), [heart rate](../metrics/heart-rate.md), [launch checklist](../product/mvp-launch.md), issue #57

## 1. Summary

Three things the launch needs and the product does not have yet, built in this order, one pull request each:

1. **A minimal profile:** the unit system (metric or imperial) and the maximum heart rate of the user, stored on the server, shown and edited on the web and on both phones.
2. **Heart-rate zones:** the time an activity spent in each of five zones, computed from the profile's maximum heart rate.
3. **The map on the phone:** the track of an activity, drawn on Android and iOS from the copy of the server that [ADR-0025](../adr/0025-device-side-cache-of-server-activities.md) already keeps.

## 2. Goals and non-goals

**Goals:** one profile per account, readable and editable from every client; units applied to every distance, pace and elevation the clients draw; time-in-zone that never goes stale when the maximum heart rate changes; the track on both phones, offline, from the copy.

**Non-goals (v1):** a name, an avatar, a birth date or a sex (a profile here is two settings, not a social profile, which is MVP-4); an age-based estimate of the maximum heart rate; custom zone limits; zone-based training plans; offline map tiles and navigation (MVP-3); a heart-rate reserve (Karvonen) model, which needs a resting heart rate.

## 3. Functional requirements

| ID | Requirement |
|---|---|
| P1 | `GET /v1/profile` answers the caller's profile; an account that never saved one gets the defaults (`metric`, no maximum heart rate). |
| P2 | `PUT /v1/profile` replaces the profile. `units` is `metric` or `imperial`; `maxHrBpm` is null or an integer from 100 to 230. Anything else is `422` and nothing is stored. |
| P3 | The profile is deleted with the account and is part of the export (`profile.json`). |
| P4 | The web, Android and iOS show the units of the profile in every distance, pace and elevation, and let the user change them and the maximum heart rate. |
| Z1 | The time in each zone of an activity is part of its analysis, in a form that does not depend on the maximum heart rate. |
| Z2 | The zones of an activity are computed when it is read, from that histogram and the current maximum heart rate. With no maximum heart rate there are no zones, and the clients say why. |
| M1 | Both phones draw the track of an activity on a map, with the style the server advertises (`mapStyleUrl`), from the copied track; a missing copy says it is not available offline. |

## 4. Non-functional requirements

- **Privacy.** The maximum heart rate is health-adjacent personal data: Restricted class ([data classification](../privacy/data-classification.md)), never logged, deleted with the account, exported. It is not shared with anyone (there is no social feature).
- **Reproducibility.** Zones are a metric: `hr-zones-v1`, with its spec in `docs/metrics/hr-zones.md` (written with its code), computed by one shared implementation (device and server).
- **No staleness.** The stored analysis never holds a zone: it holds what does not depend on the user's setting (Z1), so a change of the maximum heart rate needs no recomputation of any activity.
- **Self-hosting.** One migration, no new service.

## 5. Design

### 5.1 The profile (P1–P4)

```sql
CREATE TABLE user_profiles (
    owner_id    uuid PRIMARY KEY REFERENCES users (id),
    units       text NOT NULL CHECK (units IN ('metric', 'imperial')),
    max_hr_bpm  smallint CHECK (max_hr_bpm BETWEEN 100 AND 230),
    updated_at  timestamptz NOT NULL DEFAULT now()
);
```

The DTO is `shared/api` `UserProfile(units, maxHrBpm)`, validated by `UserProfile.problem()` and not by an `init` block: a rule that lives in `init` is reported by the serialization library as a decoding error, which is a different answer from the `422` this contract promises ([issue #80](https://github.com/AlessioBrillo/averyn/issues/80)).

The routes live in a new `profile` package of the backend, next to `consent`: authenticated, owner-only, `403` for a deleted account. `PUT` is an upsert; there is no `PATCH`, because the whole profile is two fields.

### 5.2 Zones (Z1, Z2)

Five zones by share of the maximum heart rate: Z1 below 60 %, Z2 from 60 % to below 70 %, Z3 from 70 % to below 80 %, Z4 from 80 % to below 90 %, Z5 from 90 %. Every bpm belongs to exactly one zone, so the zones always add up to the time with a heart rate.

The analysis gains a histogram of the milliseconds spent at each bpm (the schema version of the analysis goes from 2 to 3, which the store already handles by recomputing a stale analysis when it is read, [ADR-0023](../adr/0023-activity-analysis-storage.md)). `GET /v1/activities/{id}/analysis` adds the zones, computed at read time from the histogram and the profile. The spec of the metric is written with its code, in the pull request that implements this part.

### 5.3 The map on the phone (M1)

MapLibre Native on both apps, in the detail of an activity, with `mapStyleUrl` from the client configuration and the track from the copy of the server on the device (ADR-0025): no new endpoint. The track is drawn as one line, with the pauses as breaks, like the web.

## 6. Test strategy

| Level | What it covers |
|---|---|
| `shared/api` | the validation of the profile (every bound, both sides), the JSON shape |
| `backend` | profile: defaults, round trip, replace, validation (`422`, nothing stored), another user's profile is not visible, deleted account `403`, deletion with the account, export |
| `shared/analysis` | the histogram; the zones: boundaries, every bpm in one zone, no heart rate, a changed maximum |
| device matrix | rows S32–S34 (profile on the phone, zones, the map offline), added with the code of each part |

## 7. Rollout and migration

V12 adds a table; nothing existing changes. The analysis schema bump (5.2) makes the server recompute analyses lazily, as it did for the best efforts.

## 8. Risks and mitigations

| Risk | Mitigation |
|---|---|
| A wrong maximum heart rate gives wrong zones | The clients show the value used next to the zones; the user can change it at any time and the zones follow |
| Units applied in some places and not in others | One formatting function per client, used by every screen; a test per client over the formats |
| The map downloads tiles from a third party | The style is the instance's ([ADR-0027](../adr/0027-managed-cloud-baseline.md)); the demo style is only the development default |

## 9. Open questions

| # | Question | Status |
|---|---|---|
| Q1 | Imperial elevation in feet and pace in min/mi: any other unit (temperature)? | Closed: none; the app shows no temperature |
| Q2 | Should the zones be visible to the activity's other viewers once sharing exists? | Open, MVP-4 |

## 10. What is implemented

- **Profile (P1–P3):** this pull request. P4 follows (web, then phones).
