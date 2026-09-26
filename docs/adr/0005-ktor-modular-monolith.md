# ADR-0005: Backend — Ktor modular monolith

- **Status:** accepted
- **Date:** 2026-09-26
- **Deciders:** @AlessioBrillo

## Context and problem

One maintainer; self-hosting must be a simple deployment; the backend should reuse Kotlin code from `shared/` ([ADR-0004](0004-kotlin-multiplatform-shared-native-ui.md)).

## Options considered

1. **Microservices** — independent scaling, high operational cost; no current need.
2. **Modular monolith in Kotlin (Ktor)** — one deployable, clear internal boundaries, reuses shared code.
3. **Spring Boot** — richer ecosystem, heavier and more magic; Ktor is lighter and coroutine-native.

## Decision

Ktor, one deployable, one Gradle module, **package per domain** (`auth`, `users`, `activities`, `processing`, `metrics`, `routes`, `segments`, `social`, `integrations`, `moderation`, `admin`, `jobs`, `storage`, `audit` — created as needed, not upfront). Domains talk through explicit interfaces; no domain touches another's tables. Async work uses a Postgres-backed job queue at first (no separate broker). REST + OpenAPI, contract-first ([docs/api](../api/openapi.yaml)), URL-versioned (`/v1`).

## Consequences

- Good: simplest thing that can scale to many thousands of users on one node; trivial self-hosting.
- Cost: boundaries are convention until enforced — add an architecture test (Konsist/ArchUnit) when a second domain lands.
- Splitting out a service is allowed only for a concrete reason (independent load, isolation, team boundary), recorded as a new ADR.

## Revisit when

A domain has clearly different scaling or availability needs (routing and processing are the likely first candidates).
