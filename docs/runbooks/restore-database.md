# Restore a self-hosted instance from a backup

- **When to use:** moving to a new machine, recovering from a lost disk, or rolling back a failed upgrade.
- **Impact / downtime:** the instance is down until the restore finishes; everything written after the backup is lost.
- **Prerequisites:** a backup made with [backup-database](backup-database.md); the repository at the **same version** that made the backup (`git checkout` it); `dc` as in that runbook. The volumes to restore into must not hold data: on the same machine, `dc down -v` first (this deletes the current data).

## Steps

1. Put the configuration back: `cp "$B/.env" .`, and `cp "$B/seaweedfs-s3.local.json" .` if the backup has it.
2. Create the containers and empty volumes without starting anything: `dc up --no-start`.
3. Restore the volumes except the database:

   ```sh
   for v in storage-data idp-bootstrap oidc-config caddy-data; do
     [ -f "$B/$v.tgz" ] || continue
     docker run --rm -v "averyn_$v:/v" -v "$B:/b:ro" alpine sh -c "tar xzf /b/$v.tgz -C /v"
   done
   ```

4. Start the database alone and load the dump: `dc up -d db`, wait until `dc ps db` says healthy, then `gunzip -c "$B/db.sql.gz" | dc exec -T db psql -q -U averyn -d postgres`. Two errors are expected and harmless: the role and the database named after `POSTGRES_USER` already exist (the image created them).
5. Start everything: `dc up -d`.
6. **Repeat deletions** ([ADR-0017](../adr/0017-data-deletion-and-retention.md)): the backup may hold activities or accounts that users deleted after it was taken. Delete them again (from your deletion requests, or by asking affected users) before reopening the instance.

## Verify

- `dc ps`: every service running, health checks healthy, `idp-init` exited `0`.
- `/ready` answers `{"status":"ready"}`; signing in to the web app with an existing user shows their activities, and a detail page opens.
- The identity provider still publishes the same issuer (`/.well-known/openid-configuration`).

## Rollback

The backup is untouched by a restore: fix the cause, `dc down -v`, and start again from step 1.
