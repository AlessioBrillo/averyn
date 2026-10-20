# ADR-0023: Activity analysis — derived data in PostgreSQL, the raw file stays the source

- **Status:** accepted
- **Date:** 2026-10-20
- **Deciders:** @AlessioBrillo

## Context and problem

MVP-2 needs the analysis of an activity: the automatic splits, the elevation, the heart rate over the time and
the series the web app draws. ADR-0016 makes the uploaded JSON Lines file the system of record: the server stores
it byte-for-byte and derives everything else from it. ADR-0016 also says the derived data goes into PostgreSQL,
versioned by `algorithm_version`.

What the analysis adds is a lot of numbers: a split every kilometer of a ten-hour activity is a few hundred
rows, and the series of the chart is a few thousand points. The questions are where they live, how big they are
and what happens when an algorithm changes.

The report (§10) warns explicitly against putting every GPS sample in a relational table without evaluating the
volume, and asks for a hybrid strategy: raw samples, normalized samples, simplified geometry, aggregates, data
for the charts and data for the advanced analysis.

## Options considered

1. **One row per sample in PostgreSQL.** Every metric becomes a query, and a cross-activity analysis (the personal
   records of a later increment) is a join. But a ten-hour activity is 36 000 rows and an imported file can hold
   200 000 points: the table grows with the length of the activities, every read of a chart pulls thousands of
   rows, and ADR-0016 already rejected it.
2. **The full series in the object store, next to the raw file.** The chart data is a file like the raw file. But
   it is a second object to write, to keep, to delete and to export, and the analysis would have to be read from
   the store while the splits come from the database: two sources for one number.
3. **The summaries in PostgreSQL, the series as a bounded JSONB document.** One row per activity with the
   structured numbers (the elevation, the coverage) in columns, the splits in one row each, and the series as one
   JSONB document that is decimated to a bounded number of points. The raw file stays the only source of truth;
   everything here is derived and can be recomputed.

## Decision

We choose **Option 3**.

- `activity_analyses` holds one row per activity: the algorithm versions, the length of a split, the elevation
  (gain, loss, minimum, maximum, mean, coverage), the quality of the heart rate and the series as one JSONB
  document.
- `activity_splits` holds one row per split. The splits are the numbers a later increment queries (the best
  efforts, the laps), so they are rows and not a JSON array: the report asks for the fields that are queried,
  related or secured to stay structured.
- The series is a **bounded, decimated** drawing: at most 2 000 points of the track and 1 000 of the heart rate.
  It is not the source of the numbers, and it is not stored at full resolution: the raw file is the source, and a
  full-resolution series would be a second copy of the activity that nobody would read.
- The derived rows are **deleted with the activity** (a foreign key with `ON DELETE CASCADE`), so the deletion of
  an activity leaves nothing behind and the export holds them with the rest.
- The derived data **does not count against the storage quota**: the quota is about the files the user stored
  (TDD-0005 §5.5), and the analysis is a few tens of kilobytes that the server recomputes from the file anyway.

## Consequences

- Good: the analysis is stored, so the web app does not replay a ten-hour activity on every page; the splits are
  queryable; the whole analysis of an account fits in the export, next to the raw files.
- Good: the version of every algorithm is stored with the numbers, so a change of algorithm is visible and never
  mixed silently (report §4.3).
- Bad / costs: a change of an algorithm leaves the stored analyses outdated. They are recomputed on the next read
  (TDD-0009), and a command recomputes them all at once.
- Bad / costs: the series is lossy. It is a drawing; the numbers to trust are the splits and the totals.
- Follow-ups: cross-activity analysis (the personal records of a later increment) will need the samples or a
  per-sample table. ADR-0016 already says to revisit that then, and not before.

## Revisit when

The first increment that has to query the samples of many activities at once (the personal records) needs the
data this decision does not keep.
