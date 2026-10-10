# Upgrade a self-hosted instance

- **When to use:** moving to a newer version of the repository (a release tag or a commit on `main`).
- **Impact / downtime:** a few minutes while images are rebuilt and services restart. Database migrations run on start.
- **Prerequisites:** a fresh backup ([backup-database](backup-database.md)); `dc` as in that runbook; the version you upgrade from noted (`git rev-parse HEAD`).

Migrations only go forward: the backend applies its Flyway migrations at start and the identity provider upgrades its own schema; neither can be undone. **Going back to an older version means restoring the backup taken before the upgrade**, so make one every time.

## Steps

1. Back up ([backup-database](backup-database.md)) and note the current version: `git rev-parse HEAD`.
2. Get the new version: `git fetch && git checkout <tag or commit>`.
3. Read what changed for operators: new variables in `.env.example`, accepted ADRs, the self-hosting guide. Add new required variables to `.env`.
4. Rebuild and restart: `dc up -d --build`. Services restart in dependency order, each waiting for the previous to be healthy.
5. Watch the start: `dc logs -f backend` until it serves (`/ready`). A failed migration stops the backend; it does not serve half-migrated.

## Verify

- `dc ps`: everything running and healthy, `idp-init` exited `0`.
- `/ready` is `{"status":"ready"}`; sign in, list and open an activity; an app syncs.

## Rollback

`dc down` (keep the volumes for diagnosis, or `-v` to start clean), `git checkout <previous version>`, then [restore-database](restore-database.md) from the backup of step 1. Restarting the old version on the already-migrated data is not supported.
