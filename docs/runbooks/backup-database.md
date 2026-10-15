# Back up a self-hosted instance

- **When to use:** on a schedule (daily is a sensible default), and always before an upgrade.
- **Impact / downtime:** the backend and web app are stopped for the few minutes the copy takes, so the database and the object store are captured in a consistent state. The identity provider stays up.
- **Prerequisites:** shell on the host, in `infrastructure/compose`; the same Compose command you start the stack with (add `-f docker-compose.tls.yml` in TLS mode). Below, `dc` stands for it, e.g. `dc() { docker compose --env-file .env "$@"; }`.

What a backup contains: both databases (Averyn and the identity provider, with their roles), the raw activity files, the identity provider's bootstrap and OIDC client volumes, the certificates (TLS mode), `.env` and `seaweedfs-s3.local.json`. Users' data is **Restricted** ([data classification](../privacy/data-classification.md)): store backups encrypted and expire them within the retention period you state to your users (ADR-0017 suggests at most 35 days).

## Steps

1. Pick a directory outside the repository, readable only by you:

   ```sh
   B=/srv/averyn-backups/$(date -u +%Y%m%dT%H%M%SZ); mkdir -p "$B" && chmod 700 "$B"
   ```

2. Stop the writers: `dc stop web backend`.
3. Dump both databases with their roles: `dc exec -T db pg_dumpall -U averyn | gzip > "$B/db.sql.gz"` (use your `POSTGRES_USER`).
4. Stop the object store so its files are at rest: `dc stop storage`.
5. Copy the volumes (the Compose project is named `averyn`, so the volumes are `averyn_<name>`):

   ```sh
   for v in storage-data idp-bootstrap oidc-config caddy-data; do
     docker volume inspect "averyn_$v" >/dev/null 2>&1 || continue   # caddy-data exists in TLS mode only
     docker run --rm -v "averyn_$v:/v:ro" -v "$B:/b" alpine tar czf "/b/$v.tgz" -C /v .
   done
   ```

6. Copy the configuration: `cp .env "$B/"; [ -f seaweedfs-s3.local.json ] && cp seaweedfs-s3.local.json "$B/"`.
7. Start again: `dc up -d`.

## Verify

- `ls -l "$B"` shows `db.sql.gz`, `storage-data.tgz`, `idp-bootstrap.tgz`, `oidc-config.tgz` (and `caddy-data.tgz` in TLS mode), none empty; `gunzip -t "$B/db.sql.gz"` succeeds.
- `dc ps` shows every service running (healthy where it has a health check).
- A backup you never restored is a hope: run [restore-database](restore-database.md) on a spare machine from time to time.

## Rollback

Nothing to roll back: if a step fails, `dc up -d` brings the stack back as it was.
