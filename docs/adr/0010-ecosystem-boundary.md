# ADR-0010: Ecosystem boundary — Averyn is the platform, Synapse is an optional integration

- **Status:** accepted
- **Date:** 2026-09-27
- **Deciders:** @AlessioBrillo

## Context and problem

A companion project, Synapse (open bio-sensing hardware, see [`docs/strategy/`](../strategy/README.md)), proposes selling a sensor device whose margin funds a managed cloud shared with Averyn. The original proposal ([`docs/strategy/03-synapse-cloud-adr.md`](../strategy/03-synapse-cloud-adr.md)) designs Synapse Cloud as a second full backend: its own auth (Keycloak config mislabeled "Authentik"), its own Postgres/TimescaleDB, its own object store (MinIO, already rejected for Averyn by [ADR-0006](0006-postgres-postgis-s3-storage.md)), its own REST API, serving both a research audience and Averyn users. The review in [`docs/strategy/review-2026-09-27.md`](../strategy/review-2026-09-27.md) documents why that duplicates identity, storage and API surface Averyn already owns, and produces a 16+ container self-host stack — directly against vision principles 9 ("extensible without prematurely becoming microservices") and 10 ("sustainable for a single developer").

Averyn must decide, before any Synapse-consuming code is written, where the line sits.

## Options considered

1. **Two independent backends** — as originally proposed. Most flexible for Synapse to also serve non-Averyn researchers, but duplicates users/devices/sessions/storage/auth, and self-hosting means running two stacks.
2. **Averyn is the only backend; Synapse Cloud is a stateless inference worker.** Averyn owns users, devices, sessions, consent and storage. It hands a job (input refs, opaque ids, no PII beyond what's needed) to a worker process that runs ONNX inference and returns a result; the worker keeps no database of its own. Researchers who want raw multi-modal data use the Synapse device SDK directly (BLE/serial/LSL), bypassing Averyn and any worker entirely.
3. **Everything inside the Averyn monolith** — run ONNX inference (e.g. via ONNX Runtime's Java bindings) in the Ktor process itself. Simplest possible deployment, but couples Averyn's release cycle to Python/ML-model iteration and loses the ability to scale or swap the inference implementation independently.

## Decision

**Option 2.** Averyn (Ktor, PostgreSQL/PostGIS, S3-compatible storage — all already decided in ADR-0004/0005/0006) is the **only** system of record for users, devices, sessions, consent and files, for both self-hosted and managed deployments, and for any sensor vendor, not just Synapse. A Synapse inference worker is an optional, separately-deployable process that:

- Has no user database, no auth system, no object store of its own.
- Receives a job through a queue Averyn controls (job id, a reference to input data Averyn already stored, the requested computation type and version); returns a result keyed to that job id.
- Never receives a raw user identifier — only what a job needs to run.
- Is entirely optional: self-hosters who don't own Synapse hardware never run it, and Averyn functions completely without it (see [ADR-0014](0014-sensor-integration-contract.md) on the Band being one sensor among others, never a dependency of core features).

This generalizes to any future third-party sensor/compute integration, not just Synapse: **the pattern is "Averyn owns state, the vendor's cloud is a pure function over data Averyn hands it."**

## Consequences

- Good: one auth system, one storage system, one thing to self-host for the vast majority of users (see [ADR-0011](0011-identity-oidc.md)); a security or privacy review has one system of record to examine, not two.
- Good: Synapse's own device SDK and any research use (LSL, local recording) are entirely unaffected — they don't go through Averyn or this worker at all.
- Cost: the worker can't independently serve a Synapse-only web dashboard or third-party app without going through Averyn's API; if that's wanted later, it's a new decision (a public read API on Averyn, not a second backend).
- Follow-up: the worker's job/result schema is defined where the actual integration lands (tracked in [`docs/product/roadmap.md`](../product/roadmap.md), no earlier than MVP-2), not in this ADR.

## Revisit when

A concrete, funded need for Synapse to serve users who are not Averyn accounts at all (e.g. a pure research SaaS) — that is a product decision to make explicitly, not a default to fall into.
