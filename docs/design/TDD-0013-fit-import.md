# TDD-0013: FIT import and archive import

- **Status:** draft
- **Version:** 0.1
- **Author:** @AlessioBrillo
- **Date:** 2026-10-26
- **Related:** [TDD-0008](TDD-0008-gpx-import.md), [TDD-0009](TDD-0009-activity-analysis.md), [ADR-0014](../adr/0014-sensor-integration-contract.md), [ADR-0022](../adr/0022-imported-activity-provenance.md), [ADR-0026](../adr/0026-averyn-synapse-link.md), [heart rate](../metrics/heart-rate.md), [threat model](../privacy/threat-model.md), [launch checklist](../product/mvp-launch.md), issue #59

## 1. Summary

A user brings the activities they already have into Averyn: a **FIT** file from a sports watch or a bike computer, or an **archive** (a ZIP, as the platforms export it) with many of them. A FIT file becomes an activity exactly like a GPX one ([TDD-0008](TDD-0008-gpx-import.md)) — the file is kept byte-for-byte, the numbers are computed by the same shared code — with two differences that matter: the file carries a **heart rate**, which is imported, and it declares its **sport**.

## 2. Goals and non-goals

**Goals:** import a FIT activity file (track, pauses, altitude, heart rate, sport) through the same pipeline as GPX; refuse a damaged or foreign file with a reason; count what was in the file and not imported; import an archive of FIT and GPX files with a report per file.

**Non-goals (v1):** TCX; the cadence, power, temperature and speed of the file (counted, not stored; the analysis does not use them yet); laps and the sessions' own totals (the numbers are computed from the points, like for every activity); the `synapse_*` developer fields of a Synapse band ([ADR-0026](../adr/0026-averyn-synapse-link.md) level L2, only after the first FIT reader is validated on a real file); chained FIT files (only the first is read); pool swimming and other sports without a track.

## 3. Functional requirements

| ID | Requirement |
|---|---|
| F1 | `POST /v1/imports/fit` takes the file as the body (`application/vnd.ant.fit`, `application/fit` or `application/octet-stream`). |
| F2 | The file is checked before anything is stored: header, size, CRC of the header and of the file, structure, and that it is an **activity** (not a course, a workout or settings). |
| F3 | The records with a position become the track. A `timer` event that stops the timer ends a segment and the next start begins another: the gap is a pause, as in GPX. A record written while the timer is stopped is not part of the track and is counted. |
| F4 | The heart rate of the records is imported as `HR` records of the canonical file, in time order; the ones that fall in a pause are written in the pause and kept out of the metric by the replay, like the readings of a strap during a pause. A reading outside the track is counted and not stored. |
| F5 | The sport is the one of the file (`session.sport`: running, cycling, walking, hiking). `?sport=` is optional and wins when given; a file that declares none of these and has no `?sport=` is refused (`sport_unknown`). |
| F6 | The same file is imported once per owner (the SHA-256 of the file), like GPX. |
| F7 | What the importer did not read is in `ignoredExtensions`, by name: `cadence`, `power`, `speed`, `distance`, `temperature`, `developer_data_fields`, `records_without_position`, `records_while_timer_stopped`, `heart_rate_outside_track`. |
| F8 | The summary of an imported activity says `source: "fit"` and `hrSource: "fit"`; the quality grade is `UNKNOWN`, as for GPX (the file carries no accuracy). |
| F9 | An archive (`POST /v1/imports/archive`, a ZIP) imports every `.fit` and `.gpx` it holds (also gzipped), and answers one line per file: created, already imported or refused, with the reason. |

## 4. Non-functional requirements

- **Security:** the reader works on a bounded buffer (32 MB), checks every length against the end of the data, allocates nothing the file asks for, and stops at the point and pause limits of GPX. An archive is read as a stream with a limit on the number of entries and on the bytes after decompression (a decompression bomb is the attack on a ZIP).
- **Determinism:** the same file always gives the same canonical file and the same metrics.
- **Privacy:** the uploaded file is Restricted (it holds a track and a heart rate); it is never logged, and the errors carry a code and a message, never a coordinate.
- **Self-hosting:** no new service, no new dependency, one more reader in the backend.

## 5. Design

### 5.1 The reader

FIT is a binary format: a header, a stream of **definition messages** (which say how the next data messages of a local type are laid out) and **data messages**, and a CRC. `FitParser` is written from the public file format, not from an SDK: the official SDK of the format is distributed under its own licence agreement, which has not been reviewed against the AGPL, and the part an import needs is small enough not to depend on it. It reads:

- `file_id` (the type must be an activity), `session` (the sport), `event` (the timer: a stop is a pause) and `record` (time, position, altitude, heart rate, and the fields it counts);
- big and little endian files, compressed timestamp headers, developer fields (skipped by their size and counted);
- every other message, skipped by its size.

The position is in semicircles (a 32-bit integer, `180 / 2^31` degrees), the altitude is scaled by 5 and offset by 500 m, the time counts seconds from 1989-12-31 and must be a real date (a value below `0x10000000` counts from the start of the device).

The file model is the one of GPX: `ImportedTrack` (the types were renamed from `Gpx…`, they were never GPX-specific), with the heart rate and the sport added. The canonicalizer writes the heart-rate records between the samples.

### 5.2 The pipeline

`ImportService` takes the format (`ImportFormat.GPX | FIT`): the parser, the source stored with the activity and the version of the reader (`fit-import-v1`) follow from it. Everything after the parse — the quota, the objects, the row, the replay, the analysis — is the code of GPX. The export names the original by its source (`original/<id>.fit`); it used to say `.gpx` for everything.

### 5.3 The archive (second pull request)

An archive is a ZIP of activity files, in the shape the platforms export. The endpoint reads the entries one at a time, imports each through the same service and answers a report. The limits are the guard against a ZIP bomb: at most 100 entries and 256 MB after decompression per request; a bigger archive is imported in several requests (a background job is the upgrade if this proves too small).

## 6. Test strategy

| Level | What it covers |
|---|---|
| `FitParserTest` | the CRC against its published check value; a full activity; the sport; the pauses; records without a position; compressed timestamps; big endian; developer data; unknown messages; the heart rate outside the track; damaged, cut, wrong-CRC, foreign and non-activity files; limits |
| `ImportFitTest` (Testcontainers) | an upload end to end: the numbers, the heart rate and its source, the sport from the file and from the query, `sport_unknown`, duplicates, the original in the export, refusals, the media type, the sign-in |
| device matrix | S35: a file from a real watch against the watch's own numbers |

## 7. Rollout and migration

No migration: `activities.source` already allows `fit` ([ADR-0022](../adr/0022-imported-activity-provenance.md)). The export names originals by their source.

## 8. Risks and mitigations

| Risk | Mitigation |
|---|---|
| A real file differs from what the tests write (a manufacturer quirk) | The reader is strict about structure and tolerant about content; row S35 is the first real file; a file that fails is refused with a reason, never half-imported |
| The reader written from the format has a bug the tests, written by the same hand, share | The CRC is pinned to its published check value; the layout rules are in one place; S35 compares with the watch |
| A heart rate wrongly attributed to a pause | The replay already keeps a reading in a pause out of the metric; a test pins it |

## 9. Open questions

| # | Question | Status |
|---|---|---|
| Q1 | Import `lap` and the session's own totals? | Closed for v1: the numbers are computed from the points, like for every activity |
| Q2 | Read the `synapse_*` developer fields? | Open, after S35 ([ADR-0026](../adr/0026-averyn-synapse-link.md) L2) |
| Q3 | TCX? | Out of MVP-L ([roadmap](../product/roadmap.md)) |

## 10. What is implemented

- **FIT file import (F1–F8):** the reader, the heart rate in the canonical file, the route, the export naming; the web page and the archive follow.
