# ADR-0008: Project name — "Averyn" is a codename

- **Status:** accepted
- **Date:** 2026-09-26
- **Deciders:** @AlessioBrillo

## Context and problem

The project needs an identifier for the repository, package names and docs now, but the public brand is undecided ([report §24](../product/reference-report-v0.1.md)).

## Decision (interim)

Use **Averyn** as a codename. Package/bundle root: `dev.averyn`. Keep the name in as few places as possible (Gradle group, package root, bundle IDs, `README`) so a rename is mechanical.

## Decision (2026-10-15, tester builds)

Tester builds on Play internal testing and TestFlight need identifiers that cannot change afterwards, so the interim decision is made final for the **technical** identifiers only:

- Application id and bundle id: `dev.averyn.app` (already used by both apps). They are never shown to a user.
- The user-visible name on tester tracks is "Averyn Beta". It is a display string and changes without any migration.
- Internal and closed tester tracks only. Nothing public until the final name is decided (below), which then changes the display name, the repository and the docs, not the identifiers.

## To decide before the first public release

- Final name, with trademark, domain and app-store name availability checks.
- Whether the final name needs new identifiers. Avoid it: the identifiers above are fixed from the first tester upload; a store listing under a different name than the identifier is allowed.

## Consequences

- A rename costs a scripted find/replace plus bundle-ID migration only if done after store publication.
