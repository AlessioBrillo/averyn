# ADR-0020: Sensor raw channels in the activity store — `formatVersion`, HR in-file, RR sidecar

- **Status:** accepted
- **Date:** 2026-10-18
- **Deciders:** @AlessioBrillo

## Context and problem

TDD-0001 §5.3 reserves "sensor channels (HR, cadence, power) as separate streams keyed by time, added in MVP-2". ADR-0015 stores each activity as one append-only JSON Lines file (`<id>.jsonl`, first line `META <ActivityMetadata>`, then `SAMPLE`/`EVENT` records). MVP-2's first sensor is BLE heart rate (`bpm`), classed **Restricted** in `data-classification.md`; its raw RR intervals are **Health (special category)** per ADR-0013 and therefore subject to per-scope consent, immediate stop on revocation, and deletion of already-collected data on revocation (ADR-0021).

The file's META has no `formatVersion`, so an old parser reading a file with new record types would either crash or silently mislabel lines (`UNKNOWN_RECORD_TYPE`). Sync uploads one object per activity keyed by `(owner, clientActivityId, sha256)`, and delete/export/backup all operate per activity file — so sensor records must travel in the same unit of atomicity as the activity they belong to, *except* RR, whose lifecycle (consent-gated, deletable independently) must not be entangled with GPS+HR immutability.

## Options considered

1. **Same `.jsonl` file, `formatVersion` in META, new `HR` record; RR in a sidecar `<id>.rr.jsonl` with its own tombstone.** GPS+bpm stay immutable together; RR follows the consent lifecycle independently. One object per activity for sync, plus an optional sidecar object.
2. **One file per sensor channel (`<id>.gps.jsonl`, `<id>.hr.jsonl`, `<id>.rr.jsonl`).** Isolates formats cleanly, but multiplies the unit of sync/upload/delete/export and breaks TDD-0002's "one raw file per activity" model; a partial sync leaves the activity unreadable.
3. **Relational store now (SQLDelight/Room) instead of ADR-0015's file.** Reopens ADR-0015; deferred to when sync needs queries/diffs, not for the first sensor channel.

## Decision

**Option 1.**

- `ActivityMetadata` gains `formatVersion: Int` (META line, e.g. `META {"activityId":...,"sport":...,"startedAtMs":...,"formatVersion":2}`). `formatVersion 1` = GPS-only files already in the wild; `formatVersion 2` = GPS + `HR` records.
- New store record type `HR` (heart-rate sample, `bpm` integer plus optional `contact` flag) interleaved in the same file, written+flushed like `SAMPLE` (F3). Its fields live in a new `HeartRateSample` domain class in `shared/sensors`.
- RR intervals are **not** in the activity file: they go to a sidecar `<id>.rr.jsonl` (same JSON Lines shape, its own META with `formatVersion`), uploaded as an optional second object next to the activity's, and deletable by its own tombstone (`deleted_rr`) without touching the activity (ADR-0021).
- Forward-compat policy for the server (and any reader): `formatVersion` ≤ latest understood → accept, unknown record kinds counted as `RecordLossReason.UNKNOWN_RECORD_TYPE` loss (never a hard failure); `formatVersion` > latest understood → `422` (fail loud, never misinterpret a format we don't know). `ignoreUnknownKeys` already handles new fields inside known records.
- No new infrastructure: one more object prefix `raw/{ownerId}/{clientActivityId}/` for the sidecar, same S3/SeaweedFS.

## Consequences

- Good: MVP-2's first sensor (HR) lands without changing the sync unit, export, or backup model.
- Good: RR deletion on consent revocation is a single object/tombstone operation; GPS+HR data survive.
- Cost: readers must handle `HR` records and the sidecar; `list()`/`interrupted()`/`metadataOf()` ignore unknown kinds (a corrupt trailing `HR` line counts as a loss like any other).
- Follow-up: cadence/power channels later are new record kinds in the same file, `formatVersion` bumped per breaking change; a sidecar only when consent/lifecycle demands it (review #17 of the Synapse review: one canonical file per activity).

## Revisit when

A second sensor channel with a different consent or deletion lifecycle needs its own sidecar, or `formatVersion` needs a breaking change (then bump to 3 and document both readers).
