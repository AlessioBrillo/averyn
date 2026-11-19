# Web app

React + TypeScript + Vite web app: the activity list and detail, the map and the
analysis charts. The design is in
[TDD-0003](../../docs/design/TDD-0003-web-activity-view.md).

## Requirements

- Node 22+.
- A running backend on `http://localhost:8080` for the dev server (or set
  `AVERYN_BACKEND` to another origin).

## Commands

```sh
npm ci
npm run dev        # Vite dev server, /v1 proxied to AVERYN_BACKEND or localhost:8080
npm run check      # oxlint + tsc + vitest
npm run build      # tsc -b && vite build
npm run preview    # serve the production build
```

The Compose stack serves the same build through Caddy at
<http://localhost:8082>; the browser talks to one origin and Caddy forwards
`/v1` to the backend.

## Configuration

The app reads `GET /v1/client-config` for the OIDC issuer and client ids, the
map style and the API version. The privacy-policy and terms URLs are optional
and are set by the instance operator. There is **no admin area**: the operator
uses runbooks and the database until moderation arrives with MVP-4
([overview](../../docs/architecture/overview.md)).
