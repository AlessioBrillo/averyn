# ADR-0019: Crash reporting and tester builds — store vitals only, no SDK

- **Status:** proposed
- **Date:** 2026-10-15
- **Deciders:** @AlessioBrillo

## Context and problem

[ADR-0009](0009-observability-baseline.md) forbids third-party analytics SDKs in the core and defers crash reporting to "a later ADR". The first real-device campaign (device matrix S1–S14) needs two things now: a way to learn that a tester's build crashed, and a way to ship builds that identify themselves. The app records location; a crash payload (stack traces, breadcrumbs, device identifiers) is exactly the kind of data [data classification](../privacy/data-classification.md) rule 7 keeps out of third-party hands.

## Options considered

1. **A crash-reporting SDK (hosted or self-hosted Sentry-compatible).** Best traces, but adds a dependency to the core, a network path from the tracking app, and a payload we would have to scrub for coordinates and tokens before we can prove rule 7. Premature before a backend endpoint and an opt-in UI exist.
2. **Store vitals only.** Play Console (Android vitals, ANR and crash clusters) and App Store Connect / TestFlight crash reports collect crashes of tester builds without any code in the app, from testers who accepted the store's own terms. Less detail, no custom context.
3. **Nothing.** Rejected: a crash during a 3 h recording would go unnoticed except by the tester's report.

## Decision

We choose **option 2 for tester builds**. No crash SDK, no remote telemetry code in the apps. The on-device `diagnostics-v2` report ([TDD-0006 §5.5](../design/TDD-0006-field-readiness.md)) stays the primary artifact: it is exported by the tester, contains no coordinates, and carries the build id (version, commit) so that a store crash report and a matrix row can be matched.

Tester builds:

- Distributed only through Play internal testing and TestFlight, never as public listings (see [ADR-0008](0008-project-codename.md): technical ids fixed, display name "Averyn Beta").
- Signed in CI with keys stored as repository secrets; a release is a manual workflow on a `v*` tag.
- The commit SHA is compiled into the app and shown in diagnostics.

## Consequences

- Good: zero new runtime dependencies, nothing leaves the device unless the tester exports it, nothing to scrub.
- Bad / costs: coarse crash information (no breadcrumbs); depends on the testers opting into store crash sharing.
- Follow-ups: if self-hosters or a public beta need crash reports, write the opt-in, self-hostable design as a new ADR (endpoint, scrubbing tests, consent UI) before adding any code.

## Revisit when

A beta beyond the author's own testers, or a crash class that store reports cannot explain.
