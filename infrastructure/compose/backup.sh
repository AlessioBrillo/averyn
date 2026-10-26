#!/bin/sh
# Backs up an instance into the directory given as the first argument (default: ./backups/<UTC time>).
# This is docs/runbooks/backup-database.md as a script: it stops the writers for the few minutes the copy takes.
#
#   COMPOSE   the Compose command you start the stack with        (default: docker compose --env-file .env)
#   ENV_FILE  the env file it uses, copied into the backup          (default: .env)
#   BACKUP_UPLOAD_CMD  optional shell command that sends "$B" off site, for example
#                      'restic backup "$B"' or 'rclone copy "$B" remote:averyn-backups/$(basename "$B")'.
#                      Encrypt there: the backup holds secrets and the data of every user.
set -eu

dc() { ${COMPOSE:-docker compose --env-file .env} "$@"; }
env_file="${ENV_FILE:-.env}"
B="${1:-backups/$(date -u +%Y%m%dT%H%M%SZ)}"
mkdir -p "$B"
chmod 700 "$B"
B=$(cd "$B" && pwd)
db_user=$(sed -n 's/^POSTGRES_USER=//p' "$env_file" | head -n 1)
db_user="${db_user:-averyn}"

trap 'dc up -d >/dev/null 2>&1 || true' EXIT

dc stop web backend
dc exec -T db pg_dumpall -U "$db_user" | gzip > "$B/db.sql.gz"
dc stop storage
for v in storage-data idp-bootstrap oidc-config caddy-data; do
  docker volume inspect "averyn_$v" >/dev/null 2>&1 || continue # caddy-data exists in TLS mode only
  docker run --rm -v "averyn_$v:/v:ro" -v "$B:/b" alpine tar czf "/b/$v.tgz" -C /v .
done
cp "$env_file" "$B/.env"
[ -f seaweedfs-s3.local.json ] && cp seaweedfs-s3.local.json "$B/"
dc up -d --wait
trap - EXIT

gunzip -t "$B/db.sql.gz"
for f in "$B"/*.tgz "$B/db.sql.gz"; do
  [ -s "$f" ] || { echo "empty file in the backup: $f" >&2; exit 1; }
done
if [ -n "${BACKUP_UPLOAD_CMD:-}" ]; then
  export B
  sh -c "$BACKUP_UPLOAD_CMD"
fi
echo "backup written to $B"
