# ADR-0029: An upload with unusable lines keeps the valid ones and counts the rest

- **Status:** accepted
- **Date:** 2026-10-31
- **Deciders:** @AlessioBrillo

## Context and problem

Since #80 the server answers `422` when a line of an uploaded activity file is complete JSON but breaks the model (a latitude of 123, a heart rate of 500), and the client then marks the activity `FAILED_PERMANENT`: it is never uploaded again. A recorder never writes such a line, so the rule looked safe. It is not:

- The range checks of the model can change between builds (they did: a heart rate above 240, a bpm of 300, an RR in 1..3000 ms). A file written by an older build can then hold a value the newer server rejects, and that activity can never sync.
- One damaged value among thousands of good samples costs the whole activity, on the device and on the server alike, when every other part of the project says the opposite: no silent data loss, every drop counted with a reason (TDD-0001 F5).

## Options considered

1. **Keep the refusal.** Simple, strict. An activity can be stranded for good by one line, and the user has no way out.
2. **Accept the file, count the unusable lines, keep the rest** (chosen). The server already counts the lines it cannot use for a cut last line (`TRUNCATED_RECORD`); this is the same treatment for `INVALID_VALUE`.
3. **Repair the line on the way in.** Rejected: raw samples are immutable and a repair would be a guess.

## Decision

**Option 2.** The server stores the file exactly as it came (the hash and the object are of the uploaded bytes) and counts every unusable line, whatever its reason, in `dropped_records` of the activity, which the summary already returns as `droppedRecords`. The metrics, the analysis and the records are computed from the lines that can be used, as for a truncated line. The `422` stays for what cannot be an activity at all: no readable metadata line, an id that does not match the URL, an unsupported format version, a file that is not finished.

## Consequences

- Good: an activity is never stranded by a value a later build would not accept; the loss is visible in the summary and in the diagnostics instead of being a silent refusal.
- Good: no change in the wire format and no client change: the client already treats `200`/`201` as done.
- Bad / costs: a file damaged by hand is accepted if its header is valid. The count is the only signal, and there is no per-reason breakdown on the server yet.
- Follow-ups: a per-reason breakdown in the summary if the campaign shows a need for it.

## Revisit when

The campaign shows `droppedRecords` above zero on uploads from real devices: the cause is then fixed at the recorder, not here.
