# ADR-0009: Observability baseline

- **Status:** accepted
- **Date:** 2026-09-26
- **Deciders:** @AlessioBrillo

## Context and problem

The report requires structured logging, metrics, job tracing, health checks and operational metrics from day one ([§18](../product/reference-report-v0.1.md)). Location data is sensitive, so telemetry itself is a privacy risk. Self-hosters must be able to run it without a vendor.

## Decision

### Backend

- Structured JSON logs to stdout (logback + logstash encoder) with a request/trace id.
- Metrics via Micrometer, exposed as Prometheus at `/metrics` (internal network only in the default Compose config).
- `/health` (liveness) and `/ready` (DB + storage reachable).
- Tracing via OpenTelemetry with vendor-neutral OTLP export, off by default.
- **Never log coordinates, tokens or free-text user content.** Log ids and counts.

### Mobile / web client telemetry

- **Off by default, explicit opt-in.** No third-party analytics SDKs in the core.
- Operational signals defined in advance: crash during tracking, sync failure rate, GPS quality distribution, battery drain per hour of tracking — reported as aggregate counters without location content.
- Crash reporting is a separate decision (self-hostable, e.g. Sentry-compatible endpoint) recorded in a later ADR.

## Consequences

- Good: works offline of any vendor; consistent with the privacy principle.
- Cost: less product analytics by default; the tracking PoC needs a local on-device diagnostics log (see TDD-0001) rather than remote telemetry.

## Revisit when

The managed cloud needs product analytics beyond operational metrics (must remain opt-in and documented in `docs/privacy`).
