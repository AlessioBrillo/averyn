# ADR-0024: Personal records — derived rows and a live projection, not a table of records

- **Status:** proposed
- **Date:** 2026-10-21
- **Deciders:** @AlessioBrillo

## Context and problem

MVP-2 adds the personal records: the best efforts of an account, the fastest kilometre and the fastest five
kilometres across every activity. This is the first feature that looks at many activities at once, and
ADR-0023 says so: it is the increment that has to decide what is kept for a query across activities.

Three questions have to be answered, and they are questions about the product as much as about the code:

1. **Where do the best efforts live?** The raw JSON Lines file stays the system of record (ADR-0016), so
   everything else is derived and recomputable. But a record is a query over many activities: the server
   cannot replay every file of an account on every request.
2. **What makes an activity eligible?** An imported GPX file carries no accuracy, so its quality is `UNKNOWN`;
   an activity recorded under the trees can be `POOR`. Both are honest measurements, and both could set a
   record. A personal record is a thing the user looks at and is proud of, or is annoyed by: a rule that
   surprises is worse than a rule that is conservative.
3. **What happens when the activity that holds a record is deleted?** The record must move to the next
   activity by itself. A stored record would have to be maintained on every upload and every deletion, and it
   would go stale.

## Options considered

1. **One table of records, maintained by the server.** A row per (user, sport, distance) with the activity that
   holds it. Simple to read, but every upload has to check every distance of the sport, every deletion has to
   promote the next one, and a change of the algorithm or of the eligibility has to rewrite the rows of every
   account.
2. **A table of best efforts per activity, and the record is a query over it.** The best efforts of an
   activity are computed with its analysis, in the same replay, and stored like the splits. The record is the
   fastest row of the query: nothing to maintain, nothing to go stale, and the eligibility is a rule of the
   query instead of a rule of the writes.
3. **A table of samples, and the record is computed from the samples.** The most flexible, and the most
   expensive: ADR-0016 rejected a table of samples for good reasons (a ten-hour activity is 36 000 rows, an
   imported file 200 000), and the best efforts do not need the samples: they are a property of the activity,
   not of its samples.
4. **Compute the records on every request, replaying every file.** Always correct and always too slow.

## Decision

We choose **Option 2**.

- The **best efforts of an activity** are derived data like the splits: one row per target distance of the
  sport, in `activity_best_efforts`, written in the same transaction as the analysis that computed them, and
  deleted with the activity. The version of the algorithm that produced them is stored with the analysis
  (`best_efforts_version`), and the rows of an older version are recomputed, never edited.
- The **personal record** is not stored: it is the fastest eligible best effort of the account for a sport and
  a distance, read with one query. Deleting the activity that holds a record promotes the next one by
  itself, and a change of the algorithm needs no write at all.
- **The eligibility is a rule of the query**, written in one place: the activity must have finished
  (`COMPLETED`), it must come from Averyn or from a GPX file, and its quality must not be `POOR`. An imported
  file is eligible: `UNKNOWN` means the accuracy was never measured, not that the activity is bad. The
  competitive records of a later increment (segments, leaderboards) will have a stricter rule: only the
  activities recorded by Averyn, only `GOOD` and `FAIR`. A personal record and a competitive record are
  different things and they do not share a rule.
- The **targets of the sport** live in the shared code (`bestEffortDistancesM`), with the algorithm: a change
  of the list is a change of the algorithm, and therefore a new version of it.

## Consequences

- Good: no table to keep in step, and no write on the path of an upload for the records themselves.
- Good: the record of an account is always the truth about the activities that are there: there is no window
  in which a stored record disagrees with the activities.
- Good: the effort of an activity is part of its analysis: one replay, one transaction, one export, and the
  numbers of the device and of the server are the same.
- Bad / costs: the record of an account is a query over the best efforts of its activities. It is a small
  query (an index on the owner and the sport), but it is not free at the scale of a very large account.
- Bad / costs: the activities stored before the best efforts existed have none. They are recomputed the first
  time they are read, and the command `backfillAnalysis` recomputes all of them at once. The page says how
  many are pending, instead of showing a record list that is quietly incomplete.
- Follow-ups: the records are derived, and the export holds them (`records.json`) as a convenience: the
  analysis of every activity holds the efforts they come from.

## Revisit when

The records become competitive (segments, leaderboards, public profiles): then the eligibility needs a
verification, a re-checking of the quality, and the privacy zones of the start and of the end of the track
before anything of it becomes public.
