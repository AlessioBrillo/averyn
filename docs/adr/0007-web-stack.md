# ADR-0007: Web — React + TypeScript + Vite; admin inside the web app

- **Status:** accepted
- **Date:** 2026-09-26
- **Deciders:** @AlessioBrillo

## Context and problem

The web app needs dashboards, activity analysis, maps (MapLibre GL JS), a route editor, public profile pages and an admin area ([report §5.2](../product/reference-report-v0.1.md)).

## Options considered

1. **Next.js/SSR** — good for public-page SEO, but adds a Node server to every self-host deployment.
2. **React + TS + Vite SPA** — static assets served by the reverse proxy; simplest self-hosting.
3. **Separate admin app (`apps/admin`)** — isolation, but doubles tooling for one maintainer.

## Decision

React + TypeScript (strict) + Vite SPA, oxlint (ships with the Vite template) for lint, Vitest for tests. Admin is a route area guarded by role, not a second app. Public pages that need link previews/SEO (shared activities, profiles) get server-rendered meta tags from the backend later; revisit SSR if that proves insufficient.

## Consequences

- Good: static deployment; one frontend toolchain.
- Cost: SPA SEO limitations for public pages until handled.

## Revisit when

Public pages need real SSR, or admin's release/security cadence must diverge from the user app.
