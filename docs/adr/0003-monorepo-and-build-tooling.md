# ADR-0003: Monorepo with Gradle and npm

- **Status:** accepted
- **Date:** 2026-09-26
- **Deciders:** @AlessioBrillo

## Context and problem

Mobile, backend, shared logic, web and docs evolve together (a change to the sample model touches all of them). One maintainer today. [Report §15](../product/reference-report-v0.1.md) proposes a monorepo.

## Options considered

1. **Polyrepo** — independent versioning, but cross-cutting changes need coordinated PRs; heavy for one person.
2. **Monorepo, Gradle (Kotlin/Android/backend) + npm (web) side by side** — atomic cross-cutting changes, one CI, one issue tracker.
3. **Monorepo with Bazel/Nx** — best at scale, large setup and maintenance cost with no current need.

## Decision

Single repo. Gradle Kotlin DSL with a version catalog and convention plugins (`build-logic/`) for all JVM/KMP/Android code; npm for `apps/web`; XcodeGen for the iOS project. CI jobs are path-filtered so unrelated changes don't run every job.

## Consequences

- Good: atomic changes, shared tooling, a single source of truth for versions.
- Cost: two build systems (Gradle, npm) plus Xcode; CI matrix includes macOS runners for iOS.
- The report's layout ([§15](../product/reference-report-v0.1.md)) is adapted: no `apps/admin` (admin is a route area in `apps/web`, see [ADR-0007](0007-web-stack.md)); backend is one Gradle module with package-per-domain ([ADR-0005](0005-ktor-modular-monolith.md)); `geo/` is deferred until MVP-3.

## Revisit when

Full CI exceeds ~15 minutes with path filtering, or a second team needs independent release cadence.
