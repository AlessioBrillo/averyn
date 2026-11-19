# Upgrade a self-hosted instance

- **When to use:** moving to a newer version of the repository (a commit on `main`; there are no tagged releases yet).
- **Impact / downtime:** a few minutes while images are rebuilt and services restart. Database migrations run on start.
- **Prerequisites:** a fresh backup ([backup-database](backup-database.md)); `dc` as in that runbook; the version you upgrade from noted (`git rev-parse HEAD`).

Migrations only go forward: the backend applies its Flyway migrations at start and the identity provider upgrades its own schema; neither can be undone. **Going back to an older version means restoring the backup taken before the upgrade**, so make one every time.

## Steps

1. Back up ([backup-database](backup-database.md)) and note the current version: `git rev-parse HEAD`.
2. Get the new version: `git fetch && git checkout <commit>` (until the first tag, pin the commit id you tested).
3. Read what changed for operators: new variables in `.env.example`, accepted ADRs, the self-hosting guide. Add new required variables to `.env`.

   **Object-store credentials (upgrade from a pre-2026-11 checkout).** The tracked `seaweedfs-s3.json` and its default credentials are gone. Add to `.env`:

   ```sh
   S3_ACCESS_KEY=<new random key>
   S3_SECRET_KEY=<new random secret>
   S3_CONFIG_FILE=./seaweedfs-s3.local.json
   ```

   Create `seaweedfs-s3.local.json` with the same pair (the shape is in `.env.example`). If you had customized `seaweedfs-s3.json`, move that identity to the local file first. The stored objects are unchanged; restarting `storage` and `backend` together applies the new credentials.

4. Rebuild and restart: `dc up -d --build`. Services restart in dependency order, each waiting for the previous to be healthy.
5. Watch the start: `dc logs -f backend` until it serves (`/ready`). A failed migration stops the backend; it does not serve half-migrated.

## Verify

- `dc ps`: everything running and healthy, `idp-init` exited `0`.
- `/ready` is `{"status":"ready"}`; sign in, list and open an activity; an app syncs.

## Rollback

`dc down` (keep the volumes for diagnosis, or `-v` to start clean), `git checkout <previous version>`, then [restore-database](restore-database.md) from the backup of step 1. Restarting the old version on the already-migrated data is not supported.
