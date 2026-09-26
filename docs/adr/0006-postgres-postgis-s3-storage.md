# ADR-0006: PostgreSQL/PostGIS and S3-compatible object storage

- **Status:** accepted
- **Date:** 2026-09-26
- **Deciders:** @AlessioBrillo

## Context and problem

The system stores relational data, geometries (routes, segments, privacy zones), and large blobs (original GPX/FIT/TCX files, raw samples, images, exports). See [report §5.4](../product/reference-report-v0.1.md).

## Options considered

- Relational + spatial: **PostgreSQL + PostGIS** (mature, spatial indexes, self-host friendly) vs. MySQL/SQLite spatial (weaker) vs. document DB (poor for relations/privacy queries).
- Blobs: **S3-compatible object storage** (portable across AWS/R2/B2/self-hosted) vs. filesystem (not portable to cloud) vs. blobs in Postgres (bloat, backups).
- Dev/self-host S3 implementation: MinIO's community edition and container images were discontinued, so it is not a safe default; **SeaweedFS** (Apache-2.0) or Garage are viable. Any S3-compatible store must work — the app only depends on the S3 API.

## Decision

PostgreSQL 16+ with PostGIS; schema migrations with **Flyway** (SQL, forward-only). S3-compatible storage accessed only via the S3 API; **SeaweedFS** is the default in the Compose stack.

**Raw samples are not one row each.** They are stored as compressed, immutable blobs per activity (format TBD in [TDD-0001](../design/TDD-0001-tracking-engine.md)), with normalized/derived series, simplified geometries and aggregates stored separately and versioned by `algorithm_version` ([report §5.4 "Regola importante"](../product/reference-report-v0.1.md)).

## Consequences

- Good: standard, portable, well-understood; one relational store for everything queryable.
- Cost: PostGIS requires a PostGIS-enabled image/extension in every deployment.
- Follow-up: benchmark sample-storage strategy (blob vs. partitioned table vs. columnar) before MVP-1.

## Revisit when

Sample volume makes per-activity blobs insufficient for cross-activity analytics (then consider a columnar/time-series store).
