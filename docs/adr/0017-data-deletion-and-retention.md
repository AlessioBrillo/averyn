# ADR-0017: Data deletion and retention — hard delete, tombstones, account data only

- **Status:** accepted
- **Date:** 2026-10-07
- **Deciders:** @AlessioBrillo

## Context and problem

Raw routes are **Restricted** data ([data classification](../privacy/data-classification.md)): "deletion is real" and every user can export everything. [TDD-0002](../design/TDD-0002-activity-sync.md) Q3 left retention and deletion open "before any public instance". Upload is idempotent per `(owner, clientActivityId)`, so a deleted activity could come back from a device that still holds the file. The identity lives in an external OIDC provider ([ADR-0011](0011-identity-oidc.md)) that a self-hoster may replace.

## Options considered

1. **Soft delete, purge later.** Undo is possible, but needs a scheduler (none exists, TDD-0002 excludes a worker) and keeps Restricted data after the user asked to remove it.
2. **Hard delete immediately**, plus a data-free tombstone so the same upload is refused afterwards.
3. **Also delete the user in the IdP.** The backend would hold an IdP admin credential and be bound to one IdP.

## Decision

**Option 2.**

- Deleting an activity removes the raw object and the row in one request; the object goes first, so a failure in between leaves a row without a file (a retry finishes it), never a file without a row.
- A tombstone `(owner, clientActivityId, deleted_at)` makes a later upload of that id answer `410 Gone` (the sync client already treats it as a permanent failure). It holds no coordinates, hash or metadata.
- Deleting the account removes all of the user's objects (by key prefix, orphans included), activities and tombstones, and marks the user row `deleted_at`. The row keeps only the pseudonymous `(issuer, subject)`, so that the same identity cannot silently start a new empty account while its devices still hold data; further calls answer `403`.
- Averyn deletes **its own data only**. Closing the identity is done in the IdP; the docs say so.
- Backups are the operator's: they must expire within a retention period the operator chooses and documents (guidance: at most 35 days). Restoring a backup can bring back data deleted after it was taken; the restore runbook (follow-up) must tell the operator to delete again what users deleted since.

## Consequences

- Good: no scheduler; the Restricted data is gone when the request returns; works with any OIDC provider.
- Good: the apps need no change.
- Cost: a deleted account cannot be reopened with the same IdP identity until an operator clears `deleted_at`; deletion cannot be undone (the UI asks for confirmation).
- Cost: the user row outlives the account (pseudonymous, needed for the 403).
- Follow-up: a runbook for restore-after-deletion; a documented operator action to clear `deleted_at`.

## Revisit when

A public hosted service needs a legal retention period, undo, or a deletion audit trail; or an IdP gains a standard deprovisioning hook worth using.
