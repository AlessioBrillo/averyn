# ADR-0016: Server raw activity storage — the JSONL file is the system of record

- **Status:** proposed
- **Date:** 2026-10-02
- **Deciders:** @AlessioBrillo

## Context and problem

[ADR-0006](0006-postgres-postgis-s3-storage.md) stores raw samples as immutable per-activity blobs but left the format open and asked for a benchmark before MVP-1. [ADR-0015](0015-local-activity-store.md) fixed the on-device format (JSON Lines). Sync ([TDD-0002](../design/TDD-0002-activity-sync.md)) must decide what the server keeps.

## Options considered

1. **Re-encode on the server** (e.g. a columnar or binary blob, or one row per sample). Compact or queryable, but a second format to keep in step with the device, and the stored bytes would no longer be what the user recorded.
2. **Store the uploaded file unchanged in S3**, and derive everything else (summary, simplified geometry) into PostgreSQL, versioned by `algorithm_version`.

## Decision

**Option 2.** The uploaded `.jsonl` is stored byte-for-byte at `raw/{ownerId}/{clientActivityId}.jsonl` and never rewritten. It is the system of record; PostgreSQL holds only derived data (summary metrics, a `LineString`, quality grade), which can be recomputed from the raw file when an algorithm version changes. The server reads the file with the same `shared/tracking` code as the device.

## Consequences

- Good: one format everywhere; "raw samples are immutable" holds end to end; recomputation after a new `algorithm_version` needs no migration of the raw data.
- Good: no per-sample rows (3 h at 1 Hz would be ~10,800 rows per activity).
- Cost: no cross-activity sample queries; fine until analytics need them.
- Follow-up: the ADR-0006 benchmark is deferred until cross-activity analytics are on the roadmap; compression of stored objects can be added without changing the key scheme.

## Revisit when

Cross-activity analytics (training load, segments, heatmaps) need indexed sample access, or object sizes make storage cost material.
