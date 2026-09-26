# ADR-0008: Project name — "Averyn" is a codename

- **Status:** proposed
- **Date:** 2026-09-26
- **Deciders:** @AlessioBrillo

## Context and problem

The project needs an identifier for the repository, package names and docs now, but the public brand is undecided ([report §24](../product/reference-report-v0.1.md)).

## Decision (interim)

Use **Averyn** as a codename. Package/bundle root: `dev.averyn`. Keep the name in as few places as possible (Gradle group, package root, bundle IDs, `README`) so a rename is mechanical.

## To decide before the first public release

- Final name, with trademark, domain and app-store name availability checks.
- Bundle IDs (`dev.averyn.*`) **cannot change after the first store release** — resolve this before any TestFlight/Play internal track upload that is shared beyond the author.

## Consequences

- A rename costs a scripted find/replace plus bundle-ID migration only if done after store publication.
