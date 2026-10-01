# ADR-0015: Local activity store — append-only file, not a database

- **Status:** accepted
- **Date:** 2026-09-27
- **Deciders:** @AlessioBrillo

## Context and problem

TDD-0001 (§5.5, Q2/Q4) needs a durable, append-only local store for `LocationSample`s and state/pause events, written to before any sample is used for live metrics (F3), that survives process death with worst-case loss ≤ 1 flush interval (F4, ≤ 5 s / ≤ 5 samples), and that is readable back with bounded memory for a 10 h activity (no-growth-with-duration requirement, §4).

## Options considered

1. **SQLDelight** (shared SQLite driver, KMP) — structured queries, transactions, a real schema; adds a native driver dependency per platform, a schema migration story, and SQL for what MVP-0 only ever does sequentially (append, then replay in order). Better fit once MVP-1 needs querying/sync, not before.
2. **Platform-native DB (Room / GRDB)** — two separate implementations per platform, duplicating the store's logic instead of sharing it in `shared/tracking`; rejected for the same reason ADR-0004 put tracking logic in `shared/` at all.
3. **Append-only text file, one per activity, in `shared/tracking` via `kotlinx-io`** — one line per record (`SAMPLE` or `EVENT`), written and flushed immediately on arrival. A single implementation, testable on the JVM, with a trivial recovery model: read whatever complete lines exist; a truncated last line (mid-write kill) is dropped and counted, never guessed at.

## Decision

**Append-only file**, one file per activity under a per-platform activities directory, format `docs/design/TDD-0001-tracking-engine.md §5.3` sample fields plus a record-type tag, one JSON object per line (JSON Lines). Each `append` call writes and flushes synchronously before returning — the caller (native adapter) only uses a sample for live metrics after this returns, which is what F3 requires directly rather than via a separate flush timer. There is therefore no batching and no flush-interval to tune: worst-case loss on a hard kill is the one sample that was mid-write, i.e. ≤ 1 sample, better than the ≤ 5 s / ≤ 5 samples target.

`kotlinx-io` gives a portable `Sink`/`Source` over a platform file with no flush guarantee beyond the OS page cache (no `fsync`); a hard power loss (not just process/app kill) can still lose the last OS-buffered write. That gap is accepted for MVP-0 — the requirement is about crash/kill/reboot recovery, which this covers, not power-loss durability.

A relational/query-capable store is deferred to MVP-1, when sync needs to diff/query activities server-side; this store's job ends at "read one activity's records back, in order, with corruption/loss counted."

## Consequences

- Good: one implementation, no native driver dependency, trivial to unit-test against real files on the JVM, recovery logic is "read valid lines, count the rest" — no WAL/journal semantics to get wrong.
- Good: satisfies F3 more directly than a batched design (no flush-interval window at all for process-level loss).
- Cost: no queries — reading an activity means replaying its whole file; fine at MVP-0 activity sizes (bounded by streaming decode, not memory), revisited if MVP-1 sync needs random access.
- Cost: no `fsync`; a hard power cut can still lose the last unflushed OS write. Documented, not solved, here.
- Follow-up: MVP-1 sync will need to read these files to upload; the format (JSON Lines) is chosen partly because it's trivial to stream-parse from any future importer too.

## Revisit when

MVP-1 sync needs to query/diff stored activities instead of only replaying them start-to-finish, or measured recovery loss on real devices exceeds the ≤ 1 sample bound this design targets.
