#!/bin/sh
# Restores an instance from a backup made by backup.sh (the directory given as the first argument).
# This is docs/runbooks/restore-database.md as a script. Run it from infrastructure/compose, at the same version of the
# repository that made the backup. COMPOSE and ENV_FILE are as in backup.sh.
#
# It does NOT repeat the deletions made after the backup (ADR-0017): that is step 6 of the runbook, and it is yours.
set -eu

dc() { ${COMPOSE:-docker compose --env-file .env} "$@"; }
env_file="${ENV_FILE:-.env}"
B="${1:?the directory of the backup}"
B=$(cd "$B" && pwd)
[ -s "$B/db.sql.gz" ] || { echo "$B is not a backup (no db.sql.gz)" >&2; exit 1; }
db_user=$(sed -n 's/^POSTGRES_USER=//p' "$B/.env" | head -n 1)
db_user="${db_user:-averyn}"

cp "$B/.env" "$env_file"
[ -f "$B/seaweedfs-s3.local.json" ] && cp "$B/seaweedfs-s3.local.json" .
dc up --no-start
for v in storage-data idp-bootstrap oidc-config caddy-data; do
  [ -f "$B/$v.tgz" ] || continue
  docker run --rm -v "averyn_$v:/v" -v "$B:/b:ro" alpine sh -c "tar xzf /b/$v.tgz -C /v"
done
dc up -d db
for _ in $(seq 1 60); do
  [ "$(dc ps --format '{{.Health}}' db)" = healthy ] && break
  sleep 2
done
# A few "already exists" errors for the roles the image created itself are expected.
gunzip -c "$B/db.sql.gz" | dc exec -T db psql -q -U "$db_user" -d postgres >/dev/null
dc up -d --wait
echo "restored from $B; now repeat the deletions made since (runbook step 6)"
