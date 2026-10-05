# TDD-0003: Web Activity View

- **Status:** draft
- **Version:** 0.1
- **Author:** @AlessioBrillo
- **Date:** 2026-10-05
- **Related:** [TDD-0002](TDD-0002-activity-sync.md), [ADR-0006](../adr/0006-postgres-postgis-s3-storage.md), [ADR-0007](../adr/0007-web-stack.md), [ADR-0011](../adr/0011-identity-oidc.md), [speed and pace](../metrics/speed-and-pace.md), [threat model](../privacy/threat-model.md)

## 1. Summary

A signed-in user sees their uploaded activities in the web app: a list, and a detail page with the track on a map and the basic metrics. This closes the MVP-1 exit criterion "visible on the web with map + basic metrics". The browser only displays what the server computed; it never recomputes a metric.

## 2. Goals and non-goals

**Goals:** sign in from the browser against the bundled IdP; list own activities; show one activity with map and basic metrics; one origin for the web app and the API, so no CORS; self-hosters choose the basemap.

**Non-goals (v1):** splits, charts, elevation (MVP-2); imperial units and user preferences; editing visibility; public or link-shared pages; deletion and export; a CSP and TLS example (self-host hardening slice); offline web; server-side rendering.

## 3. Functional requirements

| ID | Requirement |
|---|---|
| W1 | Only the owner can list or read an activity and its track; anyone else gets `404`, never `403` (as TDD-0002 S5), also for the list and the track |
| W2 | Every displayed metric comes from the API; the browser only formats |
| W3 | Coordinates and tokens never appear in backend or web-server logs; the web server writes no access log |
| W4 | The basemap style URL is server configuration, served by `/v1/client-config`; the app has no hard-coded tile host |
| W5 | The session lives in `sessionStorage` only: closing the tab signs out |
| W6 | An activity without a usable track (fewer than two accepted samples) is shown with its metrics and a "no track" notice |

## 4. Non-functional requirements

- **Privacy:** the default basemap is a public tile service, so its operator sees which area a user views (not the track itself: the track is drawn client-side). This is stated in the self-hosting guide; a self-hoster can point `AVERYN_MAP_STYLE_URL` at their own tile server.
- **Security:** the web app is a public OIDC client (code + PKCE, no secret). A script injected into the page could read the token: accepted for now, see §8.
- **Self-hosting:** one more container (static files + reverse proxy), no new stateful service.
- **Size:** a list page is at most 100 summaries; a track is returned whole (a 10 h activity at 1 Hz is about 36 000 vertices, well under 2 MB as GeoJSON).

## 5. Design

### 5.1 Endpoints

| Endpoint | Behaviour |
|---|---|
| `GET /v1/activities?limit=&cursor=` | The caller's activities, newest first. `limit` 1–100, default 50; out of range or malformed `cursor` → `400`. Response `{items, nextCursor}`; `nextCursor` is null on the last page. |
| `GET /v1/activities/{id}/track` | The track as a GeoJSON `Feature` (`application/geo+json`); `geometry` is null when there is no track. `404` if not found or not owned. |
| `GET /v1/client-config` | Gains `webClientId` and `mapStyleUrl`. |

Pagination is keyset on `(started_at, id)`, so a page is stable while new activities arrive and uses the existing `activities_owner_started_idx`. The cursor is opaque to clients.

### 5.2 Metrics

`averageSpeedMps` and `paceSecPerKm` (null when the average speed is 0) join `ActivitySummary`. They are **stored at ingest**, not computed when read: a row declares `metrics_algorithm_version` including `speed-v1`, and computing them on read with whatever code is current would let value and version diverge as soon as `speed-v2` exists. Migration `V4` backfills existing rows with the frozen `speed-v1` formula (distance over moving time; pace `1000 / speed`). Current speed is not stored: it is a live value, meaningless for a finished activity.

Display: pace (min/km) for run, walk and hike; speed (km/h) for ride.

### 5.3 Authentication

The IdP gets a second app, `averyn-web` (user-agent type, authorization code + PKCE, refresh tokens), created by the idempotent `idp-init` in the same project as the mobile app. Both therefore share the access-token audience and the backend's validation is unchanged. Its redirect URI is `${WEB_ORIGIN}/callback`, where `WEB_ORIGIN` is the public URL of the web app and must match exactly. The SPA uses `oidc-client-ts` with scope `openid`, a `sessionStorage` user store and silent renew.

### 5.4 Serving

A Caddy container serves the built SPA (with an `index.html` fallback) and proxies `/v1/*` to the backend: one origin, no CORS configuration on the backend. `/metrics` is not proxied. No access log (W3). Caddy also gives automatic TLS later without a design change.

### 5.5 Pages

Plain `history` navigation, three states: `/` (list, or a sign-in button), `/callback` (code exchange), `/activities/{id}` (detail). No router dependency. The map is MapLibre GL JS with the track as a GeoJSON line, fitted to its bounds.

## 6. Test strategy

- **Backend (Testcontainers, as TDD-0002):** summary speed and pace equal the replayed snapshot; list order and keyset paging (3 activities, `limit=2`); user B's list never contains user A's activities; track coordinates match the accepted samples; track of someone else's activity → `404`; `400` for bad `limit` and `cursor`; `401` without a token; `client-config` carries the new fields.
- **Web (Vitest):** the formatters (0 m, 1 km, 3 h, null pace, ride vs run); the signed-out render.
- **Compose smoke (CI):** the web container serves the SPA, proxies `/v1/client-config`, and `/v1/activities` answers `401`.
- **Manual, real stack:** sign in with a browser, see an uploaded fixture in the list, open it, compare the metrics with its `expected.json`, confirm another user gets "not found", and check that logs hold no coordinates or tokens.

## 7. Rollout and migration

`V4` is forward-only and backfills in place. On an existing stack `idp-init` creates the web app at the next `up`; `WEB_ORIGIN` defaults to `http://localhost:${WEB_PORT}` and must be set to the public URL for anything beyond local use.

## 8. Risks and mitigations

| Risk | Mitigation |
|---|---|
| A token in `sessionStorage` is readable by injected script | No user-generated HTML is rendered; the app has few dependencies; a CSP lands with the hardening slice; revisit a backend-for-frontend session if the threat model requires it |
| The IdP's token endpoint may reject browser calls (CORS) | Checked first in the real-stack run; the IdP's app settings allow the web origin through the registered redirect URI |
| The default tile service disappears or changes terms | The URL is configuration; documented alternatives in the self-hosting guide |
| `WEB_ORIGIN` differs from the URL users type | Sign-in fails with a redirect mismatch; documented in the self-hosting guide |
| Very long activities make a heavy track response | Measured first; `ST_Simplify` when needed |

## 9. Open questions

| # | Question | Produces |
|---|---|---|
| Q1 | Default basemap and tile hosting for the project's own demo instance | ADR with the maps milestone (MVP-3) |
| Q2 | Does the threat model require a backend-for-frontend session? | ADR, before any public instance |
